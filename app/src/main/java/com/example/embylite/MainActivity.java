package com.example.embylite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

    private final ExecutorService executor = Executors.newFixedThreadPool(5);
    private final List<Movie> movies = new ArrayList<>();
    private SharedPreferences preferences;
    private boolean darkMode;
    private ThemePalette palette;
    private EmbyClient client;
    private String userId;
    private Movie selectedMovie;
    private LibraryMode libraryMode = LibraryMode.ALL;
    private boolean recentNewestFirst = true;
    private boolean showingDetail;
    private Movie activeCollection;
    private final Map<LibraryMode, Button> modeButtons = new EnumMap<>(LibraryMode.class);
    private Button recentSortButton;

    private enum LibraryMode {
        ALL, RECENT, FAVORITES, COLLECTIONS, COLLECTION_ITEMS
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("session", MODE_PRIVATE);
        darkMode = preferences.getBoolean(PREF_DARK_MODE, true);
        palette = ThemePalette.create(darkMode);
        applySystemBars();
        String server = preferences.getString("server", "");
        String token = preferences.getString("token", "");
        userId = preferences.getString("userId", "");
        if (!server.isEmpty() && !token.isEmpty() && !userId.isEmpty()) {
            client = new EmbyClient(server, deviceId(), token);
            showLibrary();
            loadLibrary(LibraryMode.ALL, null);
        } else {
            showLogin(server.isEmpty() ? DEFAULT_SERVER : server);
        }
    }

    private void showLogin(String savedServer) {
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
        brand.setTextColor(Color.WHITE);
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
        utilityBar.addView(theme, new LinearLayout.LayoutParams(dp(46), dp(42)));
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
                new int[]{palette.primary, palette.muted}
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
        progress.setIndeterminateTintList(ColorStateList.valueOf(palette.primary));
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

        login.setOnClickListener(v -> {
            String serverValue = server.getText().toString().trim();
            if (serverValue.isEmpty() || username.getText().toString().trim().isEmpty()) {
                toast("请填写服务器地址和用户名");
                return;
            }
            login.setEnabled(false);
            progress.setVisibility(View.VISIBLE);
            client = new EmbyClient(serverValue, deviceId(), "");
            executor.execute(() -> {
                try {
                    EmbyClient.Session session = client.login(
                            username.getText().toString().trim(),
                            password.getText().toString()
                    );
                    userId = session.userId;
                    if (remember.isChecked()) {
                        CredentialStore.save(
                                preferences,
                                username.getText().toString().trim(),
                                password.getText().toString()
                        );
                    } else {
                        CredentialStore.clear(preferences);
                    }
                    preferences.edit()
                            .putString("server", serverValue)
                            .putString("token", session.token)
                            .putString("userId", userId)
                            .apply();
                    runOnUiThread(() -> {
                        showLibrary();
                        loadLibrary(LibraryMode.ALL, null);
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        login.setEnabled(true);
                        progress.setVisibility(View.GONE);
                        toast("连接失败：" + readable(error));
                    });
                }
            });
        });
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(scroll);
    }

    private void showLibrary() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.background);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), dp(14), dp(12), dp(6));
        bar.setBackgroundColor(palette.background);

        LinearLayout titleGroup = new LinearLayout(this);
        titleGroup.setOrientation(LinearLayout.VERTICAL);
        titleGroup.setGravity(Gravity.CENTER_VERTICAL);

        TextView eyebrow = new TextView(this);
        eyebrow.setText(R.string.library_eyebrow);
        eyebrow.setTextColor(palette.primaryLight);
        eyebrow.setTextSize(11);
        eyebrow.setLetterSpacing(0.08f);
        eyebrow.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleGroup.addView(eyebrow, matchWrap());

        TextView heading = new TextView(this);
        heading.setTag("heading");
        heading.setText("我的影片");
        heading.setTextColor(palette.text);
        heading.setTextSize(27);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleGroup.addView(heading, matchWrap());
        bar.addView(titleGroup, new LinearLayout.LayoutParams(0, dp(66), 1));

        Button theme = themeToggleButton();
        theme.setOnClickListener(v -> {
            LibraryMode currentMode = libraryMode;
            Movie collection = activeCollection;
            toggleTheme();
            showLibrary();
            loadLibrary(currentMode, collection);
        });
        LinearLayout.LayoutParams themeParams = new LinearLayout.LayoutParams(dp(48), dp(44));
        themeParams.rightMargin = dp(8);
        bar.addView(theme, themeParams);

        Button logout = new Button(this);
        logout.setText("退出");
        styleChip(logout, false);
        logout.setOnClickListener(v -> {
            String server = preferences.getString("server", "");
            preferences.edit()
                    .remove("token")
                    .remove("userId")
                    .putString("server", server)
                    .apply();
            movies.clear();
            showLogin(server);
        });
        bar.addView(logout, new LinearLayout.LayoutParams(dp(72), dp(44)));
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(14), dp(5), dp(14), dp(7));
        modeButtons.clear();

        Button all = new Button(this);
        all.setText("全部");
        styleChip(all, true);
        all.setOnClickListener(v -> loadLibrary(LibraryMode.ALL, null));
        addNavButton(nav, all, LibraryMode.ALL);

        Button favorites = new Button(this);
        favorites.setText("收藏");
        styleChip(favorites, false);
        favorites.setOnClickListener(v -> loadLibrary(LibraryMode.FAVORITES, null));
        addNavButton(nav, favorites, LibraryMode.FAVORITES);

        Button recent = new Button(this);
        recent.setText("最近播放");
        styleChip(recent, false);
        recent.setOnClickListener(v -> loadLibrary(LibraryMode.RECENT, null));
        addNavButton(nav, recent, LibraryMode.RECENT);

        recentSortButton = new Button(this);
        recentSortButton.setTag("recentSort");
        recentSortButton.setText("最新优先 ↓");
        styleChip(recentSortButton, false);
        recentSortButton.setVisibility(View.GONE);
        recentSortButton.setOnClickListener(v -> {
            recentNewestFirst = !recentNewestFirst;
            recentSortButton.setText(recentNewestFirst ? "最新优先 ↓" : "最早优先 ↑");
            loadLibrary(LibraryMode.RECENT, null);
        });
        nav.addView(recentSortButton, chipParams());

        Button collections = new Button(this);
        collections.setText("合集");
        styleChip(collections, false);
        collections.setOnClickListener(v -> loadLibrary(LibraryMode.COLLECTIONS, null));
        addNavButton(nav, collections, LibraryMode.COLLECTIONS);

        TextView hint = new TextView(this);
        hint.setText("点按海报查看详情");
        hint.setGravity(Gravity.CENTER_VERTICAL);
        hint.setTextSize(13);
        hint.setTextColor(palette.muted);
        nav.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        navScroll.addView(nav);
        root.addView(navScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminateTintList(ColorStateList.valueOf(palette.primary));
        progress.setTag("progress");
        LinearLayout.LayoutParams progressParams = wrapWrap();
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.topMargin = dp(48);
        root.addView(progress, progressParams);

        GridView grid = new GridView(this);
        grid.setTag("grid");
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(154));
        grid.setHorizontalSpacing(dp(12));
        grid.setVerticalSpacing(dp(16));
        grid.setPadding(dp(14), dp(10), dp(14), dp(102));
        grid.setClipToPadding(false);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVisibility(View.GONE);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            Movie item = movies.get(position);
            if (item.collection) {
                loadLibrary(LibraryMode.COLLECTION_ITEMS, item);
            } else {
                selectedMovie = item;
                showDetail(item);
            }
        });
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        FrameLayout screen = new FrameLayout(this);
        screen.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout actionDock = new LinearLayout(this);
        actionDock.setGravity(Gravity.CENTER);
        actionDock.setPadding(dp(6), dp(6), dp(6), dp(6));
        actionDock.setBackground(rounded(palette.surface, 24, palette.border, 1));
        actionDock.setElevation(dp(12));

        Button randomButton = new Button(this);
        randomButton.setText("⤨  随机播放");
        styleActionButton(randomButton, false);
        randomButton.setOnClickListener(v -> playRandom());
        actionDock.addView(randomButton, new LinearLayout.LayoutParams(0, dp(52), 1));

        Button playButton = new Button(this);
        playButton.setText("▶  播放所选");
        styleActionButton(playButton, true);
        playButton.setOnClickListener(v -> playSelected());
        LinearLayout.LayoutParams dockPlayParams = new LinearLayout.LayoutParams(0, dp(52), 1);
        dockPlayParams.leftMargin = dp(6);
        actionDock.addView(playButton, dockPlayParams);

        FrameLayout.LayoutParams dockParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.BOTTOM);
        dockParams.setMargins(dp(14), dp(8), dp(14), dp(14));
        screen.addView(actionDock, dockParams);
        setContentView(screen);
    }

    private void loadLibrary(LibraryMode mode, Movie collection) {
        libraryMode = mode;
        showingDetail = false;
        activeCollection = mode == LibraryMode.COLLECTION_ITEMS ? collection : null;
        updateNavigation(mode);
        selectedMovie = null;
        View progress = getWindow().getDecorView().findViewWithTag("progress");
        GridView grid = getWindow().getDecorView().findViewWithTag("grid");
        TextView heading = getWindow().getDecorView().findViewWithTag("heading");
        if (progress == null || grid == null || heading == null) return;
        progress.setVisibility(View.VISIBLE);
        grid.setVisibility(View.GONE);
        if (mode == LibraryMode.ALL) heading.setText("我的影片");
        if (mode == LibraryMode.RECENT) heading.setText("最近播放");
        if (mode == LibraryMode.FAVORITES) heading.setText("我的收藏");
        if (mode == LibraryMode.COLLECTIONS) heading.setText("我的合集");
        if (mode == LibraryMode.COLLECTION_ITEMS && collection != null) {
            heading.setText(collection.name);
        }
        executor.execute(() -> {
            try {
                List<Movie> loaded;
                if (mode == LibraryMode.FAVORITES) {
                    loaded = client.loadMovies(userId, true);
                } else if (mode == LibraryMode.RECENT) {
                    loaded = RecentStore.filterAndSort(
                            preferences,
                            client.loadMovies(userId, false),
                            recentNewestFirst
                    );
                } else if (mode == LibraryMode.COLLECTIONS) {
                    loaded = client.loadCollections(userId);
                } else if (mode == LibraryMode.COLLECTION_ITEMS && collection != null) {
                    loaded = client.loadCollectionItems(userId, collection.id);
                } else {
                    loaded = client.loadMovies(userId, false);
                }
                runOnUiThread(() -> {
                    if (libraryMode != mode) return;
                    movies.clear();
                    movies.addAll(loaded);
                    for (Movie movie : movies) {
                        if (!movie.collection) {
                            selectedMovie = movie;
                            break;
                        }
                    }
                    progress.setVisibility(View.GONE);
                    grid.setVisibility(View.VISIBLE);
                    grid.setAdapter(new MovieAdapter(this, movies, client, executor, palette));
                    if (movies.isEmpty()) toast("当前分类中没有内容");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    toast("影片加载失败：" + readable(error));
                });
            }
        });
    }

    private void playSelected() {
        if (selectedMovie == null || selectedMovie.collection) {
            toast("当前列表没有可播放的影片");
            return;
        }
        play(selectedMovie);
    }

    private void playRandom() {
        List<Movie> playable = new ArrayList<>();
        for (Movie movie : movies) {
            if (!movie.collection) playable.add(movie);
        }
        if (playable.isEmpty()) {
            toast("当前列表没有可播放的影片");
            return;
        }
        selectedMovie = playable.get(new Random().nextInt(playable.size()));
        play(selectedMovie);
    }

    private void toggleFavorite(Movie movie) {
        boolean newValue = !movie.favorite;
        executor.execute(() -> {
            try {
                client.setFavorite(userId, movie.id, newValue);
                movie.favorite = newValue;
                runOnUiThread(() -> {
                    toast(newValue ? "已加入收藏" : "已取消收藏");
                    Button detailFavorite = getWindow().getDecorView()
                            .findViewWithTag("detailFavorite");
                    if (detailFavorite != null) {
                        detailFavorite.setText(newValue ? "★ 已收藏" : "☆ 收藏");
                        styleActionButton(detailFavorite, newValue);
                    }
                    if (libraryMode == LibraryMode.FAVORITES && !newValue) {
                        if (!showingDetail) loadLibrary(LibraryMode.FAVORITES, null);
                    } else {
                        GridView grid = getWindow().getDecorView().findViewWithTag("grid");
                        if (grid != null && grid.getAdapter() instanceof MovieAdapter) {
                            ((MovieAdapter) grid.getAdapter()).notifyDataSetChanged();
                        }
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> toast("收藏操作失败：" + readable(error)));
            }
        });
    }

    private void showDetail(Movie movie) {
        showingDetail = true;
        selectedMovie = movie;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(12), dp(22), dp(38));
        content.setBackgroundColor(palette.background);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        Button back = new Button(this);
        back.setText("‹ 返回");
        styleChip(back, false);
        back.setOnClickListener(v -> returnToLibrary());
        topBar.addView(back, new LinearLayout.LayoutParams(dp(92), dp(46)));

        TextView pageTitle = new TextView(this);
        pageTitle.setText("影片详情");
        pageTitle.setTextColor(palette.text);
        pageTitle.setTextSize(18);
        pageTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pageTitle.setGravity(Gravity.CENTER);
        topBar.addView(pageTitle, new LinearLayout.LayoutParams(0, dp(52), 1));

        Button theme = themeToggleButton();
        theme.setOnClickListener(v -> {
            toggleTheme();
            showDetail(movie);
        });
        topBar.addView(theme, new LinearLayout.LayoutParams(dp(48), dp(44)));
        content.addView(topBar, matchWrap());

        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(rounded(palette.surfaceHigh, 22, palette.border, 1));
        poster.setClipToOutline(true);
        LinearLayout.LayoutParams posterParams = new LinearLayout.LayoutParams(dp(224), dp(336));
        posterParams.gravity = Gravity.CENTER_HORIZONTAL;
        posterParams.topMargin = dp(20);
        poster.setElevation(dp(8));
        content.addView(poster, posterParams);

        TextView title = new TextView(this);
        title.setText(movie.name);
        title.setTextColor(palette.text);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams detailTitleParams = matchWrap();
        detailTitleParams.topMargin = dp(22);
        content.addView(title, detailTitleParams);

        TextView size = new TextView(this);
        String yearAndSize = movie.year.isEmpty() ? "" : movie.year + "  ·  ";
        size.setText(getString(R.string.year_and_size, yearAndSize, formatFileSize(movie.size)));
        size.setTextColor(palette.muted);
        size.setTextSize(15);
        size.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sizeParams = matchWrap();
        sizeParams.topMargin = dp(8);
        content.addView(size, sizeParams);

        if (!movie.overview.trim().isEmpty()) {
            TextView overview = new TextView(this);
            overview.setText(movie.overview.trim());
            overview.setTextColor(palette.muted);
            overview.setTextSize(15);
            overview.setLineSpacing(dp(3), 1f);
            overview.setGravity(Gravity.START);
            overview.setPadding(dp(18), dp(16), dp(18), dp(16));
            overview.setBackground(rounded(palette.surface, 20, palette.border, 1));
            LinearLayout.LayoutParams overviewParams = matchWrap();
            overviewParams.topMargin = dp(20);
            content.addView(overview, overviewParams);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionsParams = matchWrap();
        actionsParams.topMargin = dp(22);

        Button playButton = new Button(this);
        playButton.setText("▶ 立即播放");
        styleActionButton(playButton, true);
        playButton.setOnClickListener(v -> play(movie));
        actions.addView(playButton, new LinearLayout.LayoutParams(dp(136), dp(56)));

        Button favoriteButton = new Button(this);
        favoriteButton.setTag("detailFavorite");
        favoriteButton.setText(movie.favorite ? "★ 已收藏" : "☆ 收藏");
        styleActionButton(favoriteButton, movie.favorite);
        favoriteButton.setOnClickListener(v -> toggleFavorite(movie));
        LinearLayout.LayoutParams favoriteParams = new LinearLayout.LayoutParams(dp(116), dp(56));
        favoriteParams.leftMargin = dp(8);
        actions.addView(favoriteButton, favoriteParams);
        content.addView(actions, actionsParams);

        LinearLayout manageActions = new LinearLayout(this);
        manageActions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams manageParams = matchWrap();
        manageParams.topMargin = dp(12);

        Button collectionButton = new Button(this);
        collectionButton.setText("＋ 添加到合集");
        styleActionButton(collectionButton, false);
        collectionButton.setOnClickListener(v -> chooseCollection(movie));
        manageActions.addView(collectionButton, new LinearLayout.LayoutParams(dp(160), dp(54)));

        Button deleteButton = new Button(this);
        deleteButton.setText("删除");
        styleDangerButton(deleteButton);
        deleteButton.setOnClickListener(v -> confirmDelete(movie));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(92), dp(54));
        deleteParams.leftMargin = dp(8);
        manageActions.addView(deleteButton, deleteParams);
        content.addView(manageActions, manageParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(palette.background);
        scroll.addView(content);
        setContentView(scroll);

        executor.execute(() -> {
            try {
                android.graphics.Bitmap bitmap = client.loadPoster(movie, 600);
                if (bitmap != null) {
                    poster.post(() -> {
                        if (showingDetail && selectedMovie == movie) poster.setImageBitmap(bitmap);
                    });
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void returnToLibrary() {
        LibraryMode returnMode = libraryMode;
        Movie collection = activeCollection;
        showingDetail = false;
        showLibrary();
        loadLibrary(returnMode, collection);
    }

    private void chooseCollection(Movie movie) {
        executor.execute(() -> {
            try {
                List<Movie> collections = client.loadCollections(userId);
                runOnUiThread(() -> {
                    if (collections.isEmpty()) {
                        toast("目前没有可用合集");
                        return;
                    }
                    String[] names = new String[collections.size()];
                    for (int i = 0; i < collections.size(); i++) {
                        names[i] = collections.get(i).name;
                    }
                    new AlertDialog.Builder(this, dialogTheme())
                            .setTitle("添加到合集")
                            .setItems(names, (dialog, which) ->
                                    addToCollection(movie, collections.get(which)))
                            .setNegativeButton("取消", null)
                            .show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> toast("合集加载失败：" + readable(error)));
            }
        });
    }

    private void addToCollection(Movie movie, Movie collection) {
        executor.execute(() -> {
            try {
                client.addToCollection(collection.id, movie.id);
                runOnUiThread(() -> toast("已添加到合集：“" + collection.name + "”"));
            } catch (Exception error) {
                runOnUiThread(() -> toast("添加到合集失败：" + readable(error)));
            }
        });
    }

    private void confirmDelete(Movie movie) {
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle("删除视频？")
                .setMessage("“" + movie.name
                        + "”将从 Emby 媒体库及服务器文件系统中永久删除，此操作无法撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> deleteMovie(movie))
                .show();
    }

    private void deleteMovie(Movie movie) {
        executor.execute(() -> {
            try {
                client.deleteItem(movie.id);
                runOnUiThread(() -> {
                    toast("视频已删除");
                    returnToLibrary();
                });
            } catch (Exception error) {
                runOnUiThread(() -> toast("删除失败：" + readable(error)));
            }
        });
    }

    private void play(Movie movie) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(client.streamUrl(movie)), "video/*");
            intent.putExtra("title", movie.name);
            intent.putExtra("filename", movie.fileName());
            intent.putExtra(Intent.EXTRA_TITLE, movie.name);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            RecentStore.record(preferences, movie.id);
        } catch (ActivityNotFoundException error) {
            toast("没有找到外部视频播放器，请先安装 VLC 或其他播放器");
        } catch (Exception error) {
            toast("无法播放：" + readable(error));
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

    private void addNavButton(LinearLayout nav, Button button, LibraryMode mode) {
        modeButtons.put(mode, button);
        nav.addView(button, chipParams());
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        params.rightMargin = dp(8);
        return params;
    }

    private void updateNavigation(LibraryMode mode) {
        LibraryMode selectedMode = mode == LibraryMode.COLLECTION_ITEMS
                ? LibraryMode.COLLECTIONS : mode;
        for (Map.Entry<LibraryMode, Button> entry : modeButtons.entrySet()) {
            styleChip(entry.getValue(), entry.getKey() == selectedMode);
        }
        if (recentSortButton != null) {
            recentSortButton.setVisibility(mode == LibraryMode.RECENT ? View.VISIBLE : View.GONE);
        }
    }

    private void styleChip(Button button, boolean selected) {
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        button.setTextColor(selected ? Color.WHITE : palette.muted);
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setBackground(rounded(
                selected ? palette.primary : palette.surface,
                22,
                selected ? palette.primary : palette.border,
                1
        ));
        button.setElevation(selected ? dp(3) : dp(darkMode ? 0 : 1));
    }

    private void styleActionButton(Button button, boolean primary) {
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? Color.WHITE : palette.text);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(rounded(
                primary ? palette.primary : palette.surfaceHigh,
                17,
                primary ? palette.primary : palette.border,
                1
        ));
        button.setElevation(primary ? dp(5) : 0);
    }

    private void styleDangerButton(Button button) {
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(palette.dangerText);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(rounded(
                palette.dangerSurface,
                18,
                palette.dangerBorder,
                1
        ));
        button.setElevation(0);
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
        palette = ThemePalette.create(darkMode);
        applySystemBars();
    }

    private void applySystemBars() {
        getWindow().setStatusBarColor(palette.background);
        getWindow().setNavigationBarColor(palette.background);
        int flags = darkMode ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
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
        if (showingDetail) {
            returnToLibrary();
        } else {
            super.onBackPressed();
        }
    }

    private static String readable(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) executor.shutdownNow();
    }
}
