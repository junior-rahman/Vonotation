package voiceflow.testtools;

import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Xml;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.OutputStreamWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.xmlpull.v1.XmlSerializer;

/** Shell-only native hierarchy reader; never installed or bundled in VoiceFlow. */
public final class ReadOnlyUiHierarchy {
    private static int nodes;
    public static void main(String[] args) {
        try { read(); }
        catch (Throwable failure) { failure.printStackTrace(System.err); System.exit(1); }
    }

    private static void read() throws Exception {
        HandlerThread thread = new HandlerThread("VoiceFlowReadOnlyUiHierarchy");
        thread.start();
        UiAutomation automation = null;
        boolean connected = false;
        try {
            // These shell framework constructors/connect methods are hidden
            // from android.jar. Reflect them exactly; never use a fallback that
            // connects with flags=0 or suppresses accessibility services.
            Class<?> connectionInterface = Class.forName("android.app.IUiAutomationConnection");
            Class<?> connectionClass = Class.forName("android.app.UiAutomationConnection");
            Object connection = connectionClass.getDeclaredConstructor().newInstance();
            Constructor<UiAutomation> constructor = UiAutomation.class.getDeclaredConstructor(Looper.class, connectionInterface);
            constructor.setAccessible(true);
            automation = constructor.newInstance(thread.getLooper(), connection);
            Method connect = UiAutomation.class.getMethod("connect", int.class);
            connect.invoke(automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            connected = true;
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root == null) throw new IllegalStateException("No active native accessibility root; clear environment cannot be verified");
            try {
                XmlSerializer serializer = Xml.newSerializer();
                serializer.setOutput(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
                serializer.startDocument("UTF-8", true);
                serializer.startTag("", "hierarchy");
                serializer.attribute("", "reader", "ReadOnlyUiHierarchy");
                serializer.attribute("", "dont-suppress-accessibility-services", "true");
                writeNode(serializer, root, 0);
                serializer.endTag("", "hierarchy");
                serializer.endDocument();
                serializer.flush();
            } finally { root.recycle(); }
        } finally {
            try {
                if (connected && automation != null) UiAutomation.class.getMethod("disconnect").invoke(automation);
            } finally { thread.quitSafely(); }
        }
    }

    private static void writeNode(XmlSerializer serializer, AccessibilityNodeInfo node, int depth) throws Exception {
        if (depth > 80 || ++nodes > 10000) throw new IllegalStateException("Native hierarchy exceeds bounded traversal");
        serializer.startTag("", "node");
        serializer.attribute("", "text", clean(node.getText()));
        serializer.attribute("", "content-desc", clean(node.getContentDescription()));
        serializer.attribute("", "package", clean(node.getPackageName()));
        serializer.attribute("", "resource-id", clean(node.getViewIdResourceName()));
        serializer.attribute("", "class", clean(node.getClassName()));
        serializer.attribute("", "focused", Boolean.toString(node.isFocused()));
        serializer.attribute("", "visible-to-user", Boolean.toString(node.isVisibleToUser()));
        serializer.attribute("", "window-id", Integer.toString(node.getWindowId()));
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        serializer.attribute("", "bounds", bounds.toShortString());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { writeNode(serializer, child, depth + 1); }
            finally { child.recycle(); }
        }
        serializer.endTag("", "node");
    }

    private static String clean(CharSequence value) {
        if (value == null) return "";
        String text = value.toString(); StringBuilder output = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int code = text.codePointAt(offset); offset += Character.charCount(code);
            if (code == 9 || code == 10 || code == 13 || code >= 32 && code <= 0xD7FF || code >= 0xE000 && code <= 0xFFFD || code >= 0x10000 && code <= 0x10FFFF) output.appendCodePoint(code);
        }
        return output.toString();
    }
}
