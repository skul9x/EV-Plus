package android.text;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

/**
 * JVM unit-test implementation of android.text.SpannableString.
 * Prevents NullPointerException from stub android.jar when Car App Library methods
 * (such as CarText.create and ModelUtils.checkCarTextHasSpanType) call getSpans().
 */
public class SpannableString implements CharSequence, GetChars, Spannable {
    private final String mText;
    private final List<SpanInfo> mSpans = new ArrayList<>();

    private static class SpanInfo {
        final Object what;
        final int start;
        final int end;
        final int flags;

        SpanInfo(Object what, int start, int end, int flags) {
            this.what = what;
            this.start = start;
            this.end = end;
            this.flags = flags;
        }
    }

    public SpannableString(CharSequence source) {
        this.mText = source != null ? source.toString() : "";
        if (source instanceof Spanned) {
            Spanned spanned = (Spanned) source;
            Object[] spans = spanned.getSpans(0, spanned.length(), Object.class);
            if (spans != null) {
                for (Object span : spans) {
                    setSpan(span, spanned.getSpanStart(span), spanned.getSpanEnd(span), spanned.getSpanFlags(span));
                }
            }
        }
    }

    public static SpannableString valueOf(CharSequence source) {
        if (source instanceof SpannableString) {
            return (SpannableString) source;
        }
        return new SpannableString(source);
    }

    @Override
    public void setSpan(Object what, int start, int end, int flags) {
        mSpans.add(new SpanInfo(what, start, end, flags));
    }

    @Override
    public void removeSpan(Object what) {
        mSpans.removeIf(span -> span.what == what);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T[] getSpans(int queryStart, int queryEnd, Class<T> kind) {
        List<T> result = new ArrayList<>();
        for (SpanInfo span : mSpans) {
            if (kind == null || kind.isInstance(span.what)) {
                result.add((T) span.what);
            }
        }
        Class<?> componentType = kind != null ? kind : Object.class;
        T[] array = (T[]) Array.newInstance(componentType, result.size());
        return result.toArray(array);
    }

    @Override
    public int getSpanStart(Object tag) {
        for (SpanInfo span : mSpans) {
            if (span.what == tag) return span.start;
        }
        return -1;
    }

    @Override
    public int getSpanEnd(Object tag) {
        for (SpanInfo span : mSpans) {
            if (span.what == tag) return span.end;
        }
        return -1;
    }

    @Override
    public int getSpanFlags(Object tag) {
        for (SpanInfo span : mSpans) {
            if (span.what == tag) return span.flags;
        }
        return 0;
    }

    @Override
    public int nextSpanTransition(int start, int limit, Class type) {
        return limit;
    }

    @Override
    public int length() {
        return mText.length();
    }

    @Override
    public char charAt(int index) {
        return mText.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return mText.subSequence(start, end);
    }

    @Override
    public void getChars(int start, int end, char[] dest, int destoff) {
        mText.getChars(start, end, dest, destoff);
    }

    @Override
    public String toString() {
        return mText;
    }
}
