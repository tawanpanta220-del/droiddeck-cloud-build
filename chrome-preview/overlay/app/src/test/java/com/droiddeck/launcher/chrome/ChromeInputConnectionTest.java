package com.droiddeck.launcher.chrome;

import static org.junit.Assert.*;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public class ChromeInputConnectionTest {
    private final List<String> events = new ArrayList<>();
    private ChromeInputConnection input;

    @Before public void setUp() {
        input = new ChromeInputConnection(new View(RuntimeEnvironment.getApplication()),
                text -> events.add("text:" + text), key -> events.add("key:" + key));
    }

    @Test public void composingTextIsVisibleToKeyboardAndSentOnce() {
        input.setComposingText("e", 1);
        input.setComposingText("example", 1);
        assertEquals("example", input.getTextBeforeCursor(100, 0).toString());
        assertTrue(events.isEmpty());
        input.finishComposingText();
        input.finishComposingText();
        assertEquals(List.of("text:example"), events);
        assertEquals("", input.getTextBeforeCursor(100, 0).toString());
    }

    @Test public void commitReplacesCompositionWithoutSendingBothVersions() {
        input.setComposingText("exampl", 1);
        input.commitText("example", 1);
        input.finishComposingText();
        assertEquals(List.of("text:example"), events);
    }

    @Test public void punctuationDoesNotRepeatPriorWord() {
        input.setComposingText("example", 1);
        input.commitText("example", 1);
        input.commitText(".", 1);
        input.setComposingText("com", 1);
        input.finishComposingText();
        input.commitText("/", 1);
        assertEquals(List.of("text:example", "text:.", "text:com", "text:/"), events);
    }

    @Test public void deletingCompositionDoesNotDeleteRemoteText() {
        input.setComposingText("abc", 1);
        input.deleteSurroundingText(1, 0);
        assertEquals("ab", input.getTextBeforeCursor(100, 0).toString());
        assertTrue(events.isEmpty());
        input.finishComposingText();
        assertEquals(List.of("text:ab"), events);
    }

    @Test public void deletionBeyondLocalCompositionReachesRemoteCursor() {
        input.setComposingText("ab", 1);
        input.deleteSurroundingText(3, 0);
        assertEquals(List.of("key:65288"), events);
        input.finishComposingText();
        assertEquals(1, events.size());
    }

    @Test public void editorActionCommitsThaiBeforeEnter() {
        input.setComposingText("ภาษาไทย", 1);
        input.performEditorAction(EditorInfo.IME_ACTION_GO);
        assertEquals(List.of("text:ภาษาไทย", "key:65293"), events);
    }

    @Test public void discardedConnectionCannotReplayTextIntoAnotherField() {
        input.setComposingText("old field", 1);
        input.discard();
        assertFalse(input.finishComposingText());
        assertFalse(input.commitText("late callback", 1));
        assertTrue(events.isEmpty());
    }
}
