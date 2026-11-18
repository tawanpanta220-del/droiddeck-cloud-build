package com.ex5.music;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LinksTest {
    @Test
    public void findsLinkInsideSharedText() {
        assertEquals("https://music.apple.com/th/album/abc/123?i=456",
                Links.findAppleMusicLink("ฟังเพลงนี้ https://music.apple.com/th/album/abc/123?i=456 ด้วย"));
    }

    @Test
    public void addsHttpsAndStripsTrailingPunctuation() {
        assertEquals("https://music.apple.com/th/playlist/x/pl.1",
                Links.findAppleMusicLink("music.apple.com/th/playlist/x/pl.1)."));
        assertEquals("https://music.apple.com/us/album/1",
                Links.findAppleMusicLink("http://music.apple.com/us/album/1"));
    }

    @Test
    public void rejectsOtherHosts() {
        assertNull(Links.findAppleMusicLink("https://evil.example/music.apple.com"));
        assertNull(Links.findAppleMusicLink("https://music.apple.com.evil.example/a"));
        assertNull(Links.findAppleMusicLink("https://open.spotify.com/track/1"));
        assertNull(Links.findAppleMusicLink(null));
        assertFalse(Links.isAppleMusicUrl("javascript:alert(1)"));
        assertTrue(Links.isAppleMusicUrl("https://geo.music.apple.com/th/album/1"));
    }

    @Test
    public void classifiesAudioFiles() {
        assertTrue(Links.isAudioName("Song.FLAC"));
        assertTrue(Links.isLosslessName("a.flac"));
        assertTrue(Links.isLosslessName("b.aiff"));
        assertTrue(Links.isAudioName("c.m4a"));
        assertFalse(Links.isLosslessName("c.m4a"));
        assertFalse(Links.isAudioName("cover.jpg"));
        assertFalse(Links.isAudioName("noext"));
        assertTrue(Links.isLosslessMime("audio/flac"));
        assertTrue(Links.isLosslessMime("audio/alac"));
        assertFalse(Links.isLosslessMime("audio/mp4a-latm"));
    }

    @Test
    public void formatsTime() {
        assertEquals("0:00", Links.formatTime(-5));
        assertEquals("3:07", Links.formatTime(187_400));
    }
}
