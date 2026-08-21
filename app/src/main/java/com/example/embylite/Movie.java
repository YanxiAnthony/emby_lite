package com.example.embylite;

final class Movie {
    final String id;
    String name;
    final String year;
    final String overview;
    final String primaryImageTag;
    final String thumbImageTag;
    final String mediaSourceId;
    final String container;
    final long size;
    final boolean collection;
    boolean favorite;

    Movie(String id, String name, String year, String overview,
          String primaryImageTag, String thumbImageTag,
          String mediaSourceId, String container, long size,
          boolean collection, boolean favorite) {
        this.id = id;
        this.name = name;
        this.year = year;
        this.overview = overview;
        this.primaryImageTag = primaryImageTag;
        this.thumbImageTag = thumbImageTag;
        this.mediaSourceId = mediaSourceId;
        this.container = container;
        this.size = size;
        this.collection = collection;
        this.favorite = favorite;
    }

    String fileName() {
        String safeName = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safeName.isEmpty()) safeName = "video";
        String extension = container.isEmpty() ? "" : container.split(",")[0].trim();
        return extension.isEmpty() ? safeName : safeName + "." + extension;
    }
}
