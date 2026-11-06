package com.droiddeck.launcher.chrome;

import android.text.Editable;
import android.text.Selection;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

final class ChromeInputConnection extends BaseInputConnection {
    private final View target;
    private final Consumer<String> text;
    private final IntConsumer key;
    private boolean discarded;

    ChromeInputConnection(View target, Consumer<String> text, IntConsumer key) {
        super(target, true);
        this.target = target;
        this.text = text;
        this.key = key;
    }

    @Override public boolean commitText(CharSequence value, int cursor) {
        if (discarded) return false;
        super.commitText(value, cursor);
        drain();
        return true;
    }

    @Override public boolean setComposingText(CharSequence value, int cursor) {
        return !discarded && super.setComposingText(value, cursor);
    }

    @Override public boolean finishComposingText() {
        if (discarded) return false;
        super.finishComposingText();
        drain();
        return true;
    }

    @Override public boolean deleteSurroundingText(int before, int after) {
        if (discarded || before < 0 || after < 0) return false;
        Editable buffer = getEditable();
        int start = Math.max(0, Math.min(Selection.getSelectionStart(buffer), Selection.getSelectionEnd(buffer)));
        int end = Math.max(start, Math.max(Selection.getSelectionStart(buffer), Selection.getSelectionEnd(buffer)));
        int localBefore = Math.min(before, start);
        int localAfter = Math.min(after, buffer.length() - end);
        buffer.delete(end, end + localAfter);
        buffer.delete(start - localBefore, start);
        for (int i = 0; i < Math.min(100, before - localBefore); i++) key.accept(0xff08);
        for (int i = 0; i < Math.min(100, after - localAfter); i++) key.accept(0xffff);
        return true;
    }

    @Override public boolean sendKeyEvent(KeyEvent event) {
        if (discarded) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_DEL &&
                getEditable().length() > 0) return deleteSurroundingText(1, 0);
        if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER || event.getKeyCode() == KeyEvent.KEYCODE_TAB) {
            finishComposingText();
        }
        return target.dispatchKeyEvent(event);
    }

    @Override public boolean performEditorAction(int action) {
        if (discarded) return false;
        finishComposingText();
        key.accept(0xff0d);
        return true;
    }

    void discard() {
        discarded = true;
        clear();
    }

    private void drain() {
        String committed = getEditable().toString();
        clear();
        if (!committed.isEmpty()) text.accept(committed);
    }

    private void clear() {
        Editable buffer = getEditable();
        removeComposingSpans(buffer);
        buffer.clear();
        Selection.setSelection(buffer, 0);
    }
}
