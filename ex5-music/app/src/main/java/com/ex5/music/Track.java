package com.ex5.music;

import android.net.Uri;

final class Track {
    final Uri uri;
    final String name;

    Track(Uri uri, String name) {
        this.uri = uri;
        this.name = name;
    }

    @Override
    public String toString() {
        return (Links.isLosslessName(name) ? "◆ " : "   ") + name;
    }
}
