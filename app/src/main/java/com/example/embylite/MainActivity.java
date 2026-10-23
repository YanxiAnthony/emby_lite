package com.example.embylite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.WindowInsets;
import com.example.embylite.BrowseState.LibraryMode;
import java.util.HashSet;
import java.util.Set;
import android.provider.Settings;
import android.text.InputType;
import android.view.inputmethod.InputMethodManager;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String DEFAULT_SERVER = "http://192.168.5.3:8096";
    private static final String DEFAULT_USERNAME = "NL";
    private static final String DEFAULT_PASSWORD = "NL";
    private static final String PREF_DARK_MODE = "darkMode";
    private static final String PREF_TAG_ORDER = "tagOrder";
    private static final String TAG_DRAG_LABEL = "navTag";

    private static final LibraryMode[] DEFAULT_TAG_ORDER = {
            LibraryMode.ALL,
            LibraryMode.FAVORITES,
            LibraryMode.RECENT,
            LibraryMode.COLLECTIONS
    };

    private final ExecutorService executor = Executors.newFixedThreadPool(5);
    private final List<Movie> movies = new ArrayList<>();
    private SharedPreferences preferences;
    private boolean darkMode;
    private ThemePalette palette;
    private EmbyClient client;
    private String userId;
    private Movie selectedMovie;
    private final BrowseState browse = new BrowseState();
    private final SessionGate sessionGate = new SessionGate();
    private final ExecutorService recentExecutor = Executors.newSingleThreadExecutor();
    private PosterLoader posters;
    private final Set<String> pendingActions = new HashSet<>();
    private String detailRestoreId = "";
    private int detailScroll;
    private boolean loginRunning;
    private boolean pendingPlayback;
    private int recentDisplayVersion;
    private int pageGeneration;
    private int collectionsPosition;
    private int collectionsOffset;
    private int searchReturnPosition;
    private int searchReturnOffset;
    private boolean recentForwardOrder = false;
    private boolean randomLoading;
    private boolean showingDetail;


    private RecentStore recentStore;
    private RecentPlaybackSync recentSync;
    private boolean recentSyncRunning;
    private boolean recentSyncAgain;
    private boolean activityResumed;

    private final Handler syncHandler = new Handler(Looper.getMainLooper());
    private final Runnable recentSyncTask = new Runnable() {
        @Override
        public void run() {
            if (!activityResumed || recentSync == null) return;
            if (!recentSyncRunning
                    && (isRecentPageVisible() || pendingPlayback)) {
                requestRecentSync();
            }
            syncHandler.postDelayed(this, 60_000);
        }
    };
    private final Map<LibraryMode, Button> modeButtons = new EnumMap<>(LibraryMode.class);
    private HorizontalScrollView libraryNavigation;
    private LibraryMode[] modeOrder = DEFAULT_TAG_ORDER.clone();
    private Button draggedTagButton;
    private float tagDragX;
    private int tagDragScroll;
    private boolean tagDragScrolling;

    private final Runnable tagDragScrollTask = new Runnable() {
        @Override
        public void run() {
            if (tagDragScroll == 0 || libraryNavigation == null) {
                tagDragScrolling = false;
                return;
            }
            int before = libraryNavigation.getScrollX();
            libraryNavigation.scrollBy(tagDragScroll * dp(14), 0);
            if (libraryNavigation.getScrollX() == before) {
                tagDragScrolling = false;
                return;
            }
            reorderTagsAtDragPosition();
            libraryNavigation.postDelayed(this, 16);
        }
    };



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("session", MODE_PRIVATE);
        darkMode = preferences.getBoolean(PREF_DARK_MODE, true);
        modeOrder = loadTagOrder();
        recentForwardOrder = preferences.getBoolean("recentForwardOrder", false);
        palette = ThemePalette.create(this, darkMode);
        applySystemBars();
        String server = preferences.getString("server", "");
        String token = preferences.getString("token", "");
        userId = preferences.getString("userId", "");
        if (!server.isEmpty() && !token.isEmpty() && !userId.isEmpty()) {
            client = new EmbyClient(server, deviceId(), token);
            startRecentSession(server);
            showLibrary();
            restoreNavigation(savedInstanceState);
            reloadCurrent();
        } else {
            showLogin(server.isEmpty() ? DEFAULT_SERVER : server);
        }
    }

    private void showLogin(String savedServer) {
        sessionGate.invalidate();
        pendingActions.clear();
        randomLoading = false;
        loginRunning = false;
        stopRecentSession();
        if (posters != null) posters.close();
        posters = null;
        client = null;
        movies.clear();
        browse.ready = false;
        browse.loading = false;
        browse.error = "";
        browse.mode = LibraryMode.RECENT;
        browse.collection = null;
        detailRestoreId = "";
        ++browse.version;
        showingDetail = false;
        browse.query = "";
        String savedUsername = CredentialStore.username(preferences);
        String savedPassword = CredentialStore.password(preferences);
        showLoginForm(
                savedServer.isEmpty() ? DEFAULT_SERVER : savedServer,
                savedUsername.isEmpty() ? DEFAULT_USERNAME : savedUsername,
                savedPassword.isEmpty() ? DEFAULT_PASSWORD : savedPassword,
                CredentialStore.shouldRemember(preferences)
        );
    }

    private void showLoginForm(String serverText, String usernameText, String passwordText,
                               boolean rememberValue) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(palette.background);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setPadding(dp(16), dp(28), dp(16), dp(28));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(28), dp(22), dp(28), dp(30));
        card.setBackground(rounded(palette.surface, 28, palette.border, 1));
        card.setElevation(dp(darkMode ? 2 : 8));
        int availableWidth = getResources().getDisplayMetrics().widthPixels - dp(32);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                Math.min(dp(440), availableWidth), ViewGroup.LayoutParams.WRAP_CONTENT);
        page.addView(card, cardParams);

        LinearLayout utilityBar = new LinearLayout(this);
        utilityBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView brand = new TextView(this);
        brand.setText("  E  ");
        brand.setTextSize(16);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.setTextColor(palette.onPrimary);
        brand.setGravity(Gravity.CENTER);
        brand.setBackground(rounded(palette.primary, 13, palette.primary, 0));
        utilityBar.addView(brand, new LinearLayout.LayoutParams(dp(42), dp(42)));

        TextView brandName = new TextView(this);
        brandName.setText(R.string.brand_name);
        brandName.setTextSize(13);
        brandName.setLetterSpacing(0.08f);
        brandName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brandName.setTextColor(palette.muted);
        utilityBar.addView(brandName, new LinearLayout.LayoutParams(0, dp(42), 1));

        Button theme = themeToggleButton();
        utilityBar.addView(theme, new LinearLayout.LayoutParams(dp(48), dp(48)));
        card.addView(utilityBar, matchWrap());

        TextView title = new TextView(this);
        title.setText("欢迎回来");
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(palette.text);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.topMargin = dp(30);
        card.addView(title, titleParams);

        TextView subtitle = new TextView(this);
        subtitle.setText("连接服务器，继续你的私人观影时光");
        subtitle.setTextSize(15);
        subtitle.setTextColor(palette.muted);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(7);
        subtitleParams.bottomMargin = dp(26);
        card.addView(subtitle, subtitleParams);

        EditText server = field("服务器地址");
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(serverText);
        card.addView(server, matchWrap());

        EditText username = field("用户名");
        username.setText(usernameText);
        card.addView(username, spaced());

        EditText password = field("密码");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText(passwordText);
        card.addView(password, spaced());

        CheckBox remember = new CheckBox(this);
        remember.setText("记住账号和密码");
        remember.setTextColor(palette.muted);
        remember.setTextSize(14);
        remember.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{palette.primaryLight, palette.muted}
        ));
        remember.setChecked(rememberValue);
        card.addView(remember, spaced());

        Button login = new Button(this);
        login.setText("连接服务器  →");
        styleActionButton(login, true);
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.topMargin = dp(18);
        buttonParams.height = dp(56);
        card.addView(login, buttonParams);

        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminateTintList(ColorStateList.valueOf(palette.primaryLight));
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = wrapWrap();
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.topMargin = dp(16);
        card.addView(progress, progressParams);

        theme.setOnClickListener(v -> {
            String currentServer = server.getText().toString();
            String currentUsername = username.getText().toString();
            String currentPassword = password.getText().toString();
            boolean currentRemember = remember.isChecked();
            toggleTheme();
            showLoginForm(currentServer, currentUsername, currentPassword, currentRemember);
        });

        login.setEnabled(!loginRunning);
        progress.setVisibility(loginRunning ? View.VISIBLE : View.GONE);
        login.setOnClickListener(v -> {
            String serverValue = server.getText().toString().trim();
            String nameValue = username.getText().toString().trim();
            String passwordValue = password.getText().toString();
            boolean rememberValueNow = remember.isChecked();
            if (loginRunning) return;
            if (serverValue.isEmpty() || nameValue.isEmpty()) {
                toast(getString(R.string.login_required_fields));
                return;
            }
            loginRunning = true;
            login.setEnabled(false);
            theme.setEnabled(false);
            progress.setVisibility(View.VISIBLE);
            int session = sessionGate.invalidate();
            EmbyClient loginClient = new EmbyClient(serverValue, deviceId(), "");
            executor.execute(() -> {
                try {
                    EmbyClient.Session result = loginClient.login(nameValue, passwordValue);
                    runOnUiThread(() -> {
                        if (!isCurrentSession(session)) return;
                        loginRunning = false;
                        client = loginClient;
                        userId = result.userId;
                        if (rememberValueNow) CredentialStore.save(preferences, nameValue, passwordValue);
                        else CredentialStore.clear(preferences);
                        preferences.edit().putString("server", serverValue)
                                .putString("token", result.token).putString("userId", userId).apply();
                        startRecentSession(serverValue);
                        showLibrary();
                        loadLibrary(LibraryMode.RECENT, null);
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        if (!isCurrentSession(session)) return;
                        loginRunning = false;
                        login.setEnabled(true);
                        theme.setEnabled(true);
                        progress.setVisibility(View.GONE);
                        toast(readable(error));
                    });
                }
            });
        });

        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setScreen(scroll);
    }

    private void showLibrary() {
        LinearLayout root = column();
        root.setBackgroundColor(palette.background);
        LinearLayout header = column();

        LinearLayout searchBar = row();
        searchBar.setPadding(dp(14), dp(8), dp(14), dp(8));
        EditText search = field(getString(R.string.search_hint));
        search.setTag("searchInput");
        search.setText(browse.query);
        search.setTextSize(15);
        search.setMinHeight(dp(40));
        search.setMinimumHeight(dp(40));
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBar.addView(search, new LinearLayout.LayoutParams(0, -2, 1));
        Button submit = compactAction(action(R.string.search_action, false,
                v -> performSearch(search.getText().toString().trim())));
        LinearLayout.LayoutParams submitParams = wrapWrap();
        submitParams.leftMargin = dp(8);
        searchBar.addView(submit, submitParams);
        Button settings = compactAction(action(R.string.settings, false, v -> showSettings()));
        LinearLayout.LayoutParams settingsParams = wrapWrap();
        settingsParams.leftMargin = dp(8);
        searchBar.addView(settings, settingsParams);
        search.setOnEditorActionListener((v, id, event) -> {
            if (id != EditorInfo.IME_ACTION_SEARCH) return false;
            performSearch(search.getText().toString().trim());
            return true;
        });
        header.addView(searchBar, matchWrap());

        libraryNavigation = new HorizontalScrollView(this);
        libraryNavigation.setHorizontalScrollBarEnabled(false);
        libraryNavigation.setOnDragListener((view, event) -> handleTagDrag(event));
        LinearLayout nav = row();
        nav.setPadding(dp(14), dp(4), dp(14), dp(8));
        modeButtons.clear();
        addTagButton(nav, getString(R.string.all_movies), LibraryMode.ALL);
        addTagButton(nav, getString(R.string.favorites), LibraryMode.FAVORITES);
        addTagButton(nav, getString(R.string.recent), LibraryMode.RECENT);
        addTagButton(nav, getString(R.string.collections), LibraryMode.COLLECTIONS);
        rebuildTagOrder(nav);
        libraryNavigation.addView(nav);
        header.addView(libraryNavigation, matchWrap());

        LinearLayout context = row();
        context.setPadding(dp(18), 0, dp(14), dp(4));
        TextView title = label("", 14, true);
        title.setTag("browseTitle");
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        context.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(context, matchWrap());

        LinearLayout tools = row();
        tools.setPadding(dp(14), 0, dp(14), dp(4));
        Button up = action(R.string.back_collections, false, v -> navigateUp());
        up.setTag("browseUp");
        tools.addView(up, wrapWrap());
        header.addView(tools, matchWrap());

        LinearLayout state = column();
        state.setTag("browseState");
        state.setGravity(Gravity.CENTER);
        state.setPadding(dp(24), dp(12), dp(24), dp(12));
        ProgressBar progress = new ProgressBar(this);
        progress.setTag("progress");
        progress.setIndeterminateTintList(ColorStateList.valueOf(palette.primaryLight));
        state.addView(progress, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView message = label("", 15, true);
        message.setTag("stateMessage");
        message.setGravity(Gravity.CENTER);
        state.addView(message, spaced());
        Button retry = action(R.string.retry, false, v -> reloadCurrent());
        retry.setTag("retry");
        state.addView(retry, wrapWrap());
        header.addView(state, matchWrap());

        ScrollView headerScroll = new UiComponents(this, palette).header();
        headerScroll.addView(header);
        root.addView(headerScroll, matchWrap());

        GridView grid = new SwipeGridView();
        grid.setTag("grid");
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(148));
        grid.setHorizontalSpacing(dp(12));
        grid.setVerticalSpacing(dp(14));
        grid.setPadding(dp(14), dp(8), dp(14), dp(16));
        grid.setClipToPadding(false);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= movies.size()) return;
            captureScroll();
            Movie movie = movies.get(position);
            if (movie.collection) {
                collectionsPosition = browse.position;
                collectionsOffset = browse.offset;
                loadLibrary(LibraryMode.COLLECTION_ITEMS, movie);
            } else showDetail(movie);
        });
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= movies.size()) return false;
            Movie movie = movies.get(position);
            if (movie.collection) return false;
            confirmDelete(movie);
            return true;
        });
        FrameLayout gridArea = new FrameLayout(this);
        gridArea.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        Button sortFab = action(recentForwardOrder ? R.string.recent_order_forward
                : R.string.recent_order_reverse, true, v -> {
            recentForwardOrder = !recentForwardOrder;
            preferences.edit().putBoolean("recentForwardOrder", recentForwardOrder).apply();
            browse.position = 0;
            browse.offset = 0;
            displayRecentMovies(false);
        });
        sortFab.setTag("recentSortFab");
        sortFab.setElevation(dp(6));
        FrameLayout.LayoutParams sortFabParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        sortFabParams.rightMargin = dp(16);
        sortFabParams.bottomMargin = dp(12);
        gridArea.addView(sortFab, sortFabParams);
        root.addView(gridArea, new LinearLayout.LayoutParams(-1, 0, 1));

        boolean shortWindow = getResources().getConfiguration().screenHeightDp < 480;
        LinearLayout dock = shortWindow ? row() : column();
        dock.setPadding(dp(14), dp(8), dp(14), dp(10));
        dock.setBackgroundColor(palette.surface);
        Button first = action(R.string.recent_play_first, true, v -> playFirstRecentMovie());
        first.setTag("recentPlayFirst");
        first.setMaxLines(2);
        first.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams firstParams = shortWindow
                ? new LinearLayout.LayoutParams(0, -2, 1) : matchWrap();
        if (shortWindow) firstParams.rightMargin = dp(8);
        else firstParams.bottomMargin = dp(8);
        dock.addView(first, firstParams);
        Button random = action(R.string.random_global, false, v -> playRandom());
        random.setTag("randomPlay");
        dock.addView(random, shortWindow ? new LinearLayout.LayoutParams(0, -2, 1) : matchWrap());
        root.addView(dock, matchWrap());
        setScreen(root);
        renderLibrary(true);
    }

    private void renderLibrary(boolean restoreScroll) {
        if (showingDetail || client == null) return;
        GridView grid = tagged("grid");
        if (grid == null) return;
        if (!(grid.getAdapter() instanceof MovieAdapter)) {
            grid.setAdapter(new MovieAdapter(this, movies, posters, palette, isRecentPageVisible()));
        } else ((MovieAdapter) grid.getAdapter()).notifyDataSetChanged();
        if (restoreScroll) grid.setSelectionFromTop(browse.position, browse.offset);
        updateNavigation(browse.mode);
        TextView title = tagged("browseTitle");
        title.setText(browse.isSearching() ? getString(R.string.search_results, browse.query)
                : browse.collection != null ? browse.collection.name : getString(R.string.item_count, movies.size()));
        Button up = tagged("browseUp");
        up.setVisibility(browse.isSearching() || browse.collection != null ? View.VISIBLE : View.GONE);
        up.setText(browse.isSearching() ? R.string.clear_search : R.string.back_collections);
        View progress = tagged("progress");
        progress.setVisibility(browse.loading ? View.VISIBLE : View.GONE);
        TextView message = tagged("stateMessage");
        String empty = browse.isSearching() ? getString(R.string.search_empty_result, browse.query)
                : getString(browse.mode == LibraryMode.FAVORITES ? R.string.empty_favorites
                : browse.mode == LibraryMode.COLLECTIONS ? R.string.empty_collections : R.string.empty_library);
        message.setText(!browse.error.isEmpty() ? browse.error : browse.loading
                ? getString(R.string.loading) : empty);
        View state = tagged("browseState");
        state.setVisibility(browse.loading || !browse.error.isEmpty() || movies.isEmpty() ? View.VISIBLE : View.GONE);
        tagged("retry").setVisibility(browse.error.isEmpty() ? View.GONE : View.VISIBLE);
        updateLibraryActions();
    }

    private void loadLibrary(LibraryMode mode, Movie collection) {
        browse.navigate(mode, collection, "");
        movies.clear();
        showingDetail = false;
        selectedMovie = null;
        EditText search = tagged("searchInput");
        if (search != null) search.setText("");
        resetAdapter();
        reloadCurrent();
    }

    private void performSearch(String term) {
        if (client == null || term.isEmpty()) {
            toast(getString(R.string.search_empty_query));
            return;
        }
        if (!browse.isSearching()) {
            captureScroll();
            searchReturnPosition = browse.position;
            searchReturnOffset = browse.offset;
        }
        browse.navigate(browse.mode, browse.collection, term);
        movies.clear();
        selectedMovie = null;
        showingDetail = false;
        hideKeyboard();
        resetAdapter();
        reloadCurrent();
    }

    private void reloadCurrent() {
        if (client == null) return;
        if (isRecentPageVisible()) {
            displayRecentMovies(true);
            requestRecentSync();
            return;
        }
        int request = browse.beginLoad();
        int session = sessionGate.current();
        EmbyClient requestClient = client;
        String requestUser = userId;
        LibraryMode mode = browse.mode;
        Movie parent = browse.collection;
        String query = browse.query;
        renderLibrary(false);
        executor.execute(() -> {
            try {
                List<Movie> loaded;
                if (!query.isEmpty()) loaded = requestClient.searchMovies(requestUser, query);
                else if (mode == LibraryMode.FAVORITES) loaded = requestClient.loadMovies(requestUser, true);
                else if (mode == LibraryMode.COLLECTIONS) loaded = requestClient.loadCollections(requestUser);
                else if (mode == LibraryMode.COLLECTION_ITEMS && parent != null)
                    loaded = requestClient.loadCollectionItems(requestUser, parent.id);
                else loaded = requestClient.loadMovies(requestUser, false);
                runOnUiThread(() -> {
                    if (!isCurrentSession(session) || !browse.finish(request, "")) return;
                    movies.clear();
                    movies.addAll(loaded);
                    renderLibrary(true);
                    restoreDetailIfNeeded();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isCurrentSession(session) || !browse.finish(request, readable(error))) return;
                    if (!handleExpired(error)) renderLibrary(false);
                });
            }
        });
    }

    private void resetAdapter() {
        GridView grid = tagged("grid");
        if (grid != null) grid.setAdapter(null);
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View token = getCurrentFocus();
        if (manager != null && token != null) {
            manager.hideSoftInputFromWindow(token.getWindowToken(), 0);
        }
    }

    private void playRandom() {
        if (client == null || randomLoading || !browse.canPlay()) return;
        List<Movie> playable = new ArrayList<>();
        for (Movie movie : movies) if (!movie.collection) playable.add(movie);
        if (browse.randomScope() != BrowseState.RandomScope.GLOBAL) {
            if (playable.isEmpty()) toast(getString(R.string.no_playable));
            else startRandomPlay(playable);
            return;
        }
        randomLoading = true;
        updateLibraryActions();
        int session = sessionGate.current();
        int request = browse.version;
        int page = pageGeneration;
        EmbyClient requestClient = client;
        String requestUser = userId;
        executor.execute(() -> {
            try {
                List<Movie> loaded = requestClient.loadMovies(requestUser, false);
                runOnUiThread(() -> {
                    if (!isCurrentSession(session)) return;
                    randomLoading = false;
                    updateLibraryActions();
                    if (browse.version != request || pageGeneration != page || showingDetail) return;
                    List<Movie> candidates = new ArrayList<>();
                    for (Movie movie : loaded) if (!movie.collection) candidates.add(movie);
                    if (candidates.isEmpty()) toast(getString(R.string.no_playable));
                    else startRandomPlay(candidates);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isCurrentSession(session)) return;
                    randomLoading = false;
                    updateLibraryActions();
                    if (browse.version == request && !handleExpired(error)) toast(readable(error));
                });
            }
        });
    }

    private void playFirstRecentMovie() {
        if (!isRecentPageVisible() || !browse.ready) return;
        if (movies.isEmpty()) {
            toast(getString(R.string.recent_play_empty));
            return;
        }
        selectedMovie = movies.get(0);
        play(selectedMovie);
    }

    private void startRandomPlay(List<Movie> playable) {
        selectedMovie = playable.get(new Random().nextInt(playable.size()));
        play(selectedMovie);
    }

    private void toggleFavorite(Movie movie) {
        boolean value = !movie.favorite;
        runMutation("favorite:" + movie.id, movie,
                (api, user) -> api.setFavorite(user, movie.id, value), () -> {
                    movie.favorite = value;
                    for (Movie item : movies) if (item.id.equals(movie.id)) item.favorite = value;
                    if (browse.mode == LibraryMode.FAVORITES && !browse.isSearching() && !value)
                        movies.removeIf(item -> item.id.equals(movie.id));
                    if (isCurrentDetail(movie)) showDetail(movie);
                    else renderLibrary(false);
                    toast(getString(value ? R.string.favorite_added : R.string.favorite_removed));
                });
    }

    private void showDetail(Movie movie) {
        if (!showingDetail) captureScroll();
        else {
            ScrollView old = tagged("detailScroll");
            if (old != null && selectedMovie != null && selectedMovie.id.equals(movie.id))
                detailScroll = old.getScrollY();
        }
        showingDetail = true;
        selectedMovie = movie;
        String size = movie.size > 0 ? formatFileSize(movie.size) : getString(R.string.unknown_size);
        String metadata = movie.year.isEmpty() ? size : movie.year + "  ·  " + size;
        View page = new DetailPage(this, palette).create(movie, metadata, posters, new DetailPage.Actions() {
            public void back() { returnToLibrary(); }
            public void theme() { toggleTheme(); showDetail(movie); }
            public void play() { MainActivity.this.play(movie); }
            public void favorite() { toggleFavorite(movie); }
            public void collection() { chooseCollection(movie); }
            public void rename() { promptRename(movie); }
            public void delete() { confirmDelete(movie); }
            public boolean busy(String action) { return pendingActions.contains(action + ":" + movie.id); }
        });
        setScreen(page);
        int savedScroll = detailScroll;
        page.post(() -> { if (page.isAttachedToWindow()) ((ScrollView) page).scrollTo(0, savedScroll); });
    }

    private void returnToLibrary() {
        showingDetail = false;
        detailScroll = 0;
        selectedMovie = null;
        showLibrary();
        if (isRecentPageVisible()) displayRecentMovies(true);
    }

    private void chooseCollection(Movie movie) {
        if (client == null || !pendingActions.add("collection:" + movie.id)) return;
        updateDetailButtons(movie);
        int session = sessionGate.current();
        EmbyClient api = client;
        String user = userId;
        executor.execute(() -> {
            try {
                List<Movie> collections = api.loadCollections(user);
                runOnUiThread(() -> {
                    if (!isCurrentSession(session)) return;
                    pendingActions.remove("collection:" + movie.id);
                    updateDetailButtons(movie);
                    if (!isCurrentDetail(movie)) return;
                    if (collections.isEmpty()) { toast(getString(R.string.empty_collections)); return; }
                    String[] names = new String[collections.size()];
                    for (int i = 0; i < names.length; i++) names[i] = collections.get(i).name;
                    new AlertDialog.Builder(this, dialogTheme()).setTitle(R.string.add_collection)
                            .setItems(names, (dialog, index) -> addToCollection(movie, collections.get(index)))
                            .setNegativeButton(R.string.cancel, null).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isCurrentSession(session)) return;
                    pendingActions.remove("collection:" + movie.id);
                    updateDetailButtons(movie);
                    if (!handleExpired(error)) toast(readable(error));
                });
            }
        });
    }

    private void addToCollection(Movie movie, Movie collection) {
        runMutation("collection:" + movie.id, movie,
                (api, user) -> api.addToCollection(collection.id, movie.id),
                () -> toast(getString(R.string.collection_added, collection.name)));
    }

    private void confirmDelete(Movie movie) {
        new AlertDialog.Builder(this, dialogTheme()).setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_message, movie.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete_movie, (dialog, which) -> deleteMovie(movie)).show();
    }

    private void deleteMovie(Movie movie) {
        RecentStore store = recentStore;
        runMutation("delete:" + movie.id, movie, (api, user) -> {
            api.deleteItem(movie.id);
            if (store != null) store.remove(movie.id);
        }, () -> {
            movies.removeIf(item -> item.id.equals(movie.id));
            toast(getString(R.string.deleted));
            if (isCurrentDetail(movie)) returnToLibrary();
            else renderLibrary(false);
        });
    }

    private interface Mutation { void run(EmbyClient api, String user) throws Exception; }

    private void runMutation(String key, Movie movie, Mutation operation, Runnable success) {
        if (client == null || !pendingActions.add(key)) return;
        updateDetailButtons(movie);
        int session = sessionGate.current();
        EmbyClient api = client;
        String user = userId;
        executor.execute(() -> {
            try {
                operation.run(api, user);
                runOnUiThread(() -> {
                    if (!isCurrentSession(session)) return;
                    pendingActions.remove(key);
                    updateDetailButtons(movie);
                    success.run();
                    requestRecentSync();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isCurrentSession(session)) return;
                    pendingActions.remove(key);
                    updateDetailButtons(movie);
                    if (!handleExpired(error)) toast(readable(error));
                });
            }
        });
    }

    private void updateDetailButtons(Movie movie) {
        if (!isCurrentDetail(movie)) return;
        String[] actions = {"favorite", "collection", "rename", "delete"};
        String[] tags = {"detailFavorite", "detailCollection", "detailRename", "detailDelete"};
        for (int i = 0; i < actions.length; i++) {
            View button = tagged(tags[i]);
            if (button != null) button.setEnabled(!pendingActions.contains(actions[i] + ":" + movie.id));
        }
    }

    private boolean isCurrentDetail(Movie movie) {
        return !isDestroyed() && showingDetail && selectedMovie != null
                && selectedMovie.id.equals(movie.id);
    }

    private void promptRename(Movie movie) {
        EditText input = field(getString(R.string.rename_dialog_hint));
        input.setSingleLine(false);
        input.setHorizontallyScrolling(false);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMaxLines(4);
        input.setVerticalScrollBarEnabled(true);
        input.setText(movie.name);
        input.setSelection(input.getText().length());

        LinearLayout container = new LinearLayout(this);
        container.setPadding(dp(10), dp(6), dp(10), dp(2));
        container.addView(input, matchWrap());

        AlertDialog dialog = new AlertDialog.Builder(this, dialogTheme())
                .setTitle(R.string.rename_dialog_title)
                .setView(container)
                .setNegativeButton(R.string.rename_dialog_cancel, null)
                .setPositiveButton(R.string.rename_dialog_save, null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String newName = input.getText().toString()
                    .replaceAll("[\\r\\n]+", " ").trim();
            if (newName.isEmpty()) {
                toast(getString(R.string.rename_empty_name));
                return;
            }
            dialog.dismiss();
            if (!newName.equals(movie.name)) renameMovie(movie, newName);
        });
        input.postDelayed(() -> {
            InputMethodManager manager = (InputMethodManager)
                    getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null) manager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 100);
    }

    private void renameMovie(Movie movie, String newName) {
        runMutation("rename:" + movie.id, movie,
                (api, user) -> api.renameItem(user, movie.id, newName), () -> {
                    movie.name = newName;
                    for (Movie item : movies) if (item.id.equals(movie.id)) item.name = newName;
                    if (isCurrentDetail(movie)) showDetail(movie);
                    else renderLibrary(false);
                    toast(getString(R.string.rename_success, newName));
                });
    }

    private void play(Movie movie) {
        if (client == null || movie == null || !pendingActions.add("play:" + movie.id)) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(client.streamUrl(movie)), "video/*");
            intent.putExtra("title", movie.name);
            intent.putExtra("filename", movie.fileName());
            intent.putExtra(Intent.EXTRA_TITLE, movie.name);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            RecentStore store = recentStore;
            Movie snapshot = movie.copy();
            long openedAt = System.currentTimeMillis();
            int session = sessionGate.current();
            pendingPlayback = true;
            recentExecutor.execute(() -> {
                if (store != null) store.record(snapshot, openedAt);
                runOnUiThread(() -> {
                    if (isCurrentSession(session)) requestRecentSync();
                });
            });
        } catch (ActivityNotFoundException error) {
            new AlertDialog.Builder(this, dialogTheme()).setMessage(R.string.player_missing)
                    .setPositiveButton(R.string.close, null).show();
        } catch (Exception error) {
            toast(readable(error));
        } finally {
            syncHandler.postDelayed(() -> pendingActions.remove("play:" + movie.id), 800);
        }
    }

    private EditText field(String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setHintTextColor(palette.muted);
        field.setTextColor(palette.text);
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setPadding(dp(18), dp(4), dp(18), dp(4));
        field.setBackground(rounded(palette.surfaceHigh, 16, palette.border, 1));
        field.setBackgroundTintList(null);
        field.setSelectAllOnFocus(false);
        field.setMinHeight(dp(56));
        return field;
    }

    private void addTagButton(LinearLayout nav, CharSequence text, LibraryMode mode) {
        Button button = new Button(this);
        button.setText(text);
        styleChip(button, mode == browse.mode);
        button.setOnClickListener(v -> loadLibrary(mode, null));
        button.setOnLongClickListener(v -> startTagDrag(button, mode));
        modeButtons.put(mode, button);
        nav.addView(button, chipParams());
    }

    private void rebuildTagOrder(LinearLayout nav) {
        nav.removeAllViews();
        for (LibraryMode mode : modeOrder) {
            Button button = modeButtons.get(mode);
            if (button == null) continue;
            nav.addView(button, chipParams());

        }
    }

    private boolean startTagDrag(Button button, LibraryMode mode) {
        button.setAlpha(0.35f);
        draggedTagButton = button;
        tagDragScroll = 0;
        button.startDragAndDrop(
                ClipData.newPlainText(TAG_DRAG_LABEL, mode.name()),
                new View.DragShadowBuilder(button),
                mode,
                0
        );
        return true;
    }

    private boolean handleTagDrag(DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return event.getLocalState() instanceof LibraryMode;
            case DragEvent.ACTION_DRAG_LOCATION:
                tagDragX = event.getX();
                reorderTagsAtDragPosition();
                updateTagDragAutoScroll();
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                tagDragScroll = 0;
                return true;
            case DragEvent.ACTION_DROP:
            case DragEvent.ACTION_DRAG_ENDED:
                stopTagDrag();
                return true;
            default:
                return true;
        }
    }

    private void stopTagDrag() {
        tagDragScroll = 0;
        if (draggedTagButton != null) {
            draggedTagButton.setAlpha(1f);
            draggedTagButton = null;
        }
        saveTagOrder();
    }

    private void reorderTagsAtDragPosition() {
        if (draggedTagButton == null || libraryNavigation == null) return;
        View nav = libraryNavigation.getChildAt(0);
        if (!(nav instanceof LinearLayout)) return;
        int fromIndex = -1;
        for (int i = 0; i < modeOrder.length; i++) {
            if (modeButtons.get(modeOrder[i]) == draggedTagButton) {
                fromIndex = i;
                break;
            }
        }
        if (fromIndex < 0) return;
        float contentX = tagDragX + libraryNavigation.getScrollX();
        int toIndex = -1;
        for (int i = 0; i < modeOrder.length; i++) {
            Button button = modeButtons.get(modeOrder[i]);
            if (button != null && button != draggedTagButton
                    && contentX >= button.getLeft() && contentX <= button.getRight()) {
                toIndex = i;
                break;
            }
        }
        if (toIndex < 0) return;
        LibraryMode dragged = modeOrder[fromIndex];
        if (fromIndex < toIndex) {
            System.arraycopy(modeOrder, fromIndex + 1, modeOrder, fromIndex, toIndex - fromIndex);
        } else {
            System.arraycopy(modeOrder, toIndex, modeOrder, toIndex + 1, fromIndex - toIndex);
        }
        modeOrder[toIndex] = dragged;
        rebuildTagOrder((LinearLayout) nav);
    }

    private void updateTagDragAutoScroll() {
        View content = libraryNavigation.getChildAt(0);
        if (content == null) return;
        int direction = 0;
        if (tagDragX < dp(56) && libraryNavigation.getScrollX() > 0) {
            direction = -1;
        } else if (tagDragX > libraryNavigation.getWidth() - dp(56)
                && libraryNavigation.getScrollX() + libraryNavigation.getWidth() < content.getWidth()) {
            direction = 1;
        }
        tagDragScroll = direction;
        if (direction != 0 && !tagDragScrolling) {
            tagDragScrolling = true;
            libraryNavigation.post(tagDragScrollTask);
        }
    }

    private LibraryMode[] loadTagOrder() {
        String saved = preferences.getString(PREF_TAG_ORDER, "");
        if (!saved.isEmpty()) {
            String[] names = saved.split(",");
            List<LibraryMode> parsed = new ArrayList<>();
            for (String name : names) {
                for (LibraryMode mode : DEFAULT_TAG_ORDER) {
                    if (mode.name().equals(name)) {
                        parsed.add(mode);
                        break;
                    }
                }
            }
            if (parsed.size() == DEFAULT_TAG_ORDER.length) {
                boolean complete = true;
                for (LibraryMode mode : DEFAULT_TAG_ORDER) {
                    if (!parsed.contains(mode)) {
                        complete = false;
                        break;
                    }
                }
                if (complete) return parsed.toArray(new LibraryMode[0]);
            }
        }
        return DEFAULT_TAG_ORDER.clone();
    }

    private void saveTagOrder() {
        StringBuilder order = new StringBuilder();
        for (LibraryMode mode : modeOrder) {
            if (order.length() > 0) order.append(',');
            order.append(mode.name());
        }
        preferences.edit().putString(PREF_TAG_ORDER, order.toString()).apply();
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(8);
        return params;
    }

    private void updateNavigation(LibraryMode mode) {
        LibraryMode selectedMode = mode == LibraryMode.COLLECTION_ITEMS
                ? LibraryMode.COLLECTIONS : mode;
        for (Map.Entry<LibraryMode, Button> entry : modeButtons.entrySet()) {
            styleChip(entry.getValue(), !browse.isSearching() && entry.getKey() == selectedMode);
        }
        updateLibraryActions();
        Button selectedButton = modeButtons.get(selectedMode);
        if (libraryNavigation != null && selectedButton != null) {
            selectedButton.post(() -> {
                int target = selectedButton.getLeft()
                        - (libraryNavigation.getWidth() - selectedButton.getWidth()) / 2;
                libraryNavigation.smoothScrollTo(Math.max(0, target), 0);
            });
        }
    }

    private void switchLibraryPage(int direction) {
        if (showingDetail || browse.mode == LibraryMode.COLLECTION_ITEMS) return;
        for (int i = 0; i < modeOrder.length; i++) {
            if (modeOrder[i] != browse.mode) continue;
            int target = i + direction;
            if (target >= 0 && target < modeOrder.length) {
                loadLibrary(modeOrder[target], null);
            }
            return;
        }
    }

    private void styleChip(Button button, boolean selected) {
        new UiComponents(this, palette).button(button, selected, true, false);
        button.setSelected(selected);
    }

    private void styleActionButton(Button button, boolean primary) {
        new UiComponents(this, palette).button(button, primary, false, false);
    }

    private void styleDangerButton(Button button) {
        new UiComponents(this, palette).button(button, false, false, true);
    }

    private Button themeToggleButton() {
        Button button = new Button(this);
        button.setText(darkMode ? "☀" : "☾");
        button.setContentDescription(darkMode ? "切换到浅色模式" : "切换到深色模式");
        styleChip(button, false);
        button.setTextColor(palette.text);
        button.setTextSize(18);
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private void toggleTheme() {
        darkMode = !darkMode;
        preferences.edit().putBoolean(PREF_DARK_MODE, darkMode).apply();
        palette = ThemePalette.create(this, darkMode);
        applySystemBars();
    }

    private void applySystemBars() {
        getWindow().setStatusBarColor(palette.background);
        getWindow().setNavigationBarColor(palette.background);
        int flags = darkMode ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        flags |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().setNavigationBarDividerColor(palette.border);
        }
    }

    private int dialogTheme() {
        return darkMode ? R.style.DialogTheme_Dark : R.style.DialogTheme_Light;
    }

    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "未知";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit == 0
                ? String.format(java.util.Locale.getDefault(), "%.0f %s", value, units[unit])
                : String.format(java.util.Locale.getDefault(), "%.2f %s", value, units[unit]);
    }

    private GradientDrawable rounded(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private String deviceId() {
        String id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        return id == null ? "emby-lite-android" : id;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onBackPressed() {
        if (showingDetail) returnToLibrary();
        else if (client != null && (browse.isSearching() || browse.collection != null)) navigateUp();
        else super.onBackPressed();
    }

    private void navigateUp() {
        if (browse.isSearching()) {
            loadLibrary(browse.mode, browse.collection);
            browse.position = searchReturnPosition;
            browse.offset = searchReturnOffset;
        } else if (browse.collection != null) {
            loadLibrary(LibraryMode.COLLECTIONS, null);
            browse.position = collectionsPosition;
            browse.offset = collectionsOffset;
        }
    }

    private String readable(Exception error) {
        if (error instanceof EmbyClient.HttpException) {
            int code = ((EmbyClient.HttpException) error).statusCode;
            if (code == 401) return getString(R.string.session_expired);
            if (code == 403) return getString(R.string.permission_error);
            if (code == 404) return getString(R.string.item_unavailable);
            if (code >= 500) return getString(R.string.server_error);
        }
        return getString(error instanceof java.io.IOException
                ? R.string.network_error : R.string.request_error);
    }

    private boolean handleExpired(Exception error) {
        if (!(error instanceof EmbyClient.HttpException)
                || ((EmbyClient.HttpException) error).statusCode != 401) return false;
        logout();
        toast(getString(R.string.session_expired));
        return true;
    }

    private boolean isCurrentSession(int session) {
        return sessionGate.accepts(session) && !isDestroyed() && !isFinishing();
    }

    private void logout() {
        preferences.edit().remove("token").remove("userId").apply();
        showLogin(preferences.getString("server", ""));
    }

    private void showSettings() {
        new AlertDialog.Builder(this, dialogTheme()).setTitle(R.string.settings)
                .setItems(new String[]{getString(darkMode ? R.string.theme_light : R.string.theme_dark),
                        getString(R.string.logout), getString(R.string.settings_summary, "2.9.3")},
                        (dialog, index) -> {
                            if (index == 0) {
                                captureScroll();
                                toggleTheme();
                                showLibrary();
                            } else if (index == 1) logout();
                        }).setNegativeButton(R.string.close, null).show();
    }

    private void captureScroll() {
        GridView grid = tagged("grid");
        if (grid == null || grid.getChildCount() == 0) return;
        browse.position = grid.getFirstVisiblePosition();
        browse.offset = grid.getChildAt(0).getTop() - grid.getPaddingTop();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        captureScroll();
        state.putString("mode", browse.mode.name());
        state.putString("query", browse.query);
        state.putSerializable("collection", browse.collection);
        state.putInt("position", browse.position);
        state.putInt("offset", browse.offset);
        state.putInt("collectionsPosition", collectionsPosition);
        state.putInt("collectionsOffset", collectionsOffset);
        state.putInt("searchReturnPosition", searchReturnPosition);
        state.putInt("searchReturnOffset", searchReturnOffset);
        if (showingDetail && selectedMovie != null) {
            state.putString("detailId", selectedMovie.id);
            ScrollView scroll = tagged("detailScroll");
            state.putInt("detailScroll", scroll == null ? 0 : scroll.getScrollY());
        }
        super.onSaveInstanceState(state);
    }

    private void restoreNavigation(Bundle state) {
        if (state == null) return;
        try { browse.mode = LibraryMode.valueOf(state.getString("mode", "RECENT")); }
        catch (IllegalArgumentException ignored) {
            // Discard navigation belonging to a removed category, such as ADDED.
            browse.mode = LibraryMode.RECENT;
            resetAdapter();
            return;
        }
        browse.query = state.getString("query", "");
        Object parent = state.getSerializable("collection");
        if (parent instanceof Movie) browse.collection = (Movie) parent;
        browse.position = state.getInt("position", 0);
        browse.offset = state.getInt("offset", 0);
        collectionsPosition = state.getInt("collectionsPosition", 0);
        collectionsOffset = state.getInt("collectionsOffset", 0);
        searchReturnPosition = state.getInt("searchReturnPosition", 0);
        searchReturnOffset = state.getInt("searchReturnOffset", 0);
        detailRestoreId = state.getString("detailId", "");
        detailScroll = state.getInt("detailScroll", 0);
        EditText search = tagged("searchInput");
        if (search != null) search.setText(browse.query);
        resetAdapter();
    }

    private void restoreDetailIfNeeded() {
        if (detailRestoreId.isEmpty()) return;
        for (Movie movie : movies) {
            if (movie.id.equals(detailRestoreId)) {
                detailRestoreId = "";
                int position = browse.position;
                int offset = browse.offset;
                showDetail(movie);
                browse.position = position;
                browse.offset = offset;
                return;
            }
        }
        detailRestoreId = "";
    }

    private <T extends View> T tagged(String tag) {
        return getWindow().getDecorView().findViewWithTag(tag);
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView label(String text, int size, boolean muted) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(muted ? palette.muted : palette.text);
        return label;
    }

    private Button action(int title, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(title);
        styleActionButton(button, primary);
        button.setOnClickListener(listener);
        return button;
    }

    private Button compactAction(Button button) {
        button.setMinHeight(dp(40));
        button.setMinimumHeight(dp(40));
        button.setPadding(dp(14), dp(4), dp(14), dp(4));
        return button;
    }

    private void setScreen(View page) {
        ++pageGeneration;
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(palette.background);
        frame.addView(page, new FrameLayout.LayoutParams(-1, -1));
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });
        setContentView(frame);
        frame.requestApplyInsets();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(12);
        return params;
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class SwipeGridView extends GridView {
        private final GestureDetector swipeDetector;
        private boolean swipeHandled;

        SwipeGridView() {
            super(MainActivity.this);
            swipeDetector = new GestureDetector(
                    MainActivity.this,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDown(MotionEvent event) {
                            return true;
                        }

                        @Override
                        public boolean onFling(MotionEvent start, MotionEvent end,
                                               float velocityX, float velocityY) {
                            if (start == null || end == null) return false;
                            float deltaX = end.getX() - start.getX();
                            float deltaY = end.getY() - start.getY();
                            if (Math.abs(deltaX) < dp(72)
                                    || Math.abs(velocityX) < dp(280)
                                    || Math.abs(deltaX) <= Math.abs(deltaY) * 1.2f) {
                                return false;
                            }
                            switchLibraryPage(deltaX < 0 ? 1 : -1);
                            swipeHandled = true;
                            return true;
                        }
                    }
            );
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                swipeHandled = false;
            }
            swipeDetector.onTouchEvent(event);
            if (swipeHandled) {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                return true;
            }
            return super.dispatchTouchEvent(event);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        requestRecentSync();
        scheduleRecentSync();
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        syncHandler.removeCallbacks(recentSyncTask);
        super.onPause();
    }

    private void startRecentSession(String server) {
        stopRecentSession();
        if (posters != null) posters.close();
        posters = new PosterLoader(client);
        recentStore = new RecentStore(preferences, server, userId);
        recentSync = new RecentPlaybackSync(client, userId, recentStore);
        if (activityResumed) requestRecentSync();
        scheduleRecentSync();
    }

    private void stopRecentSession() {
        if (recentSync != null) recentSync.cancel();
        recentSync = null;
        recentStore = null;
        recentSyncRunning = false;
        recentSyncAgain = false;
        pendingPlayback = false;
        syncHandler.removeCallbacks(recentSyncTask);
    }

    private void scheduleRecentSync() {
        syncHandler.removeCallbacks(recentSyncTask);
        if (activityResumed && recentSync != null) {
            syncHandler.postDelayed(recentSyncTask, 60_000);
        }
    }

    private boolean isRecentPageVisible() {
        return recentSync != null && browse.mode == LibraryMode.RECENT
                && !showingDetail && !browse.isSearching();
    }

    private void updateLibraryActions() {
        Button first = tagged("recentPlayFirst");
        if (first != null) {
            first.setVisibility(isRecentPageVisible() ? View.VISIBLE : View.GONE);
            first.setEnabled(browse.ready && !movies.isEmpty());
            first.setText(movies.isEmpty() ? getString(R.string.recent_play_first)
                    : getString(R.string.play_first_title, movies.get(0).name));
        }
        Button sortFab = tagged("recentSortFab");
        if (sortFab != null) {
            sortFab.setVisibility(isRecentPageVisible() ? View.VISIBLE : View.GONE);
            sortFab.setText(recentForwardOrder ? R.string.recent_order_forward
                    : R.string.recent_order_reverse);
        }
        Button random = tagged("randomPlay");
        if (random != null) {
            random.setEnabled(browse.canPlay() && !randomLoading);
            random.setText(randomLoading ? R.string.random_loading : browse.isSearching()
                    ? R.string.random_results : browse.mode == LibraryMode.FAVORITES
                    ? R.string.random_favorites : R.string.random_global);
        }
    }

    private void requestRecentSync() {
        if (recentSync == null || isDestroyed()) return;
        if (recentSyncRunning) { recentSyncAgain = true; return; }
        RecentPlaybackSync sessionSync = recentSync;
        RecentStore store = recentStore;
        recentSyncRunning = true;
        executor.execute(() -> {
            sessionSync.synchronize();
            boolean hasPending = !store.pending().isEmpty();
            EmbyClient.HttpException error = sessionSync.lastHttpError();
            runOnUiThread(() -> {
                if (recentSync != sessionSync || isDestroyed()) return;
                if (error != null && handleExpired(error)) return;
                pendingPlayback = hasPending;
                recentSyncRunning = false;
                if (isRecentPageVisible()) displayRecentMovies(true);
                if (recentSyncAgain) {
                    recentSyncAgain = false;
                    requestRecentSync();
                }
            });
        });
    }

    private void displayRecentMovies(boolean preserveScroll) {
        if (!isRecentPageVisible()) return;
        int session = sessionGate.current();
        int version = browse.version;
        int displayVersion = ++recentDisplayVersion;
        RecentStore store = recentStore;
        boolean forward = recentForwardOrder;
        if (!browse.ready) browse.loading = true;
        renderLibrary(false);
        recentExecutor.execute(() -> {
            List<Movie> loaded = store.sortTimeline(store.cachedMovies(), forward);
            runOnUiThread(() -> {
                if (!isCurrentSession(session) || browse.version != version
                        || displayVersion != recentDisplayVersion || !isRecentPageVisible()) return;
                if (preserveScroll && browse.ready) captureScroll();
                movies.clear();
                movies.addAll(loaded);
                // Keep the viewport in place when playback moves a movie to another timeline group.
                int lastPosition = Math.max(0, movies.size() - 1);
                if (browse.position > lastPosition) {
                    browse.position = lastPosition;
                    browse.offset = 0;
                }
                browse.finish(version, "");
                renderLibrary(true);
                restoreDetailIfNeeded();
            });
        });
    }

    @Override
    protected void onDestroy() {
        sessionGate.invalidate();
        stopRecentSession();
        syncHandler.removeCallbacksAndMessages(null);
        if (libraryNavigation != null) libraryNavigation.removeCallbacks(tagDragScrollTask);
        if (posters != null) posters.close();
        recentExecutor.shutdown();
        super.onDestroy();
        executor.shutdownNow();
    }
}
