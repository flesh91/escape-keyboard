/*
 * Copyright (C) 2026 Yaroslav Bezzubets
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.pocketworkstation.pckeyboard.gesture.GestureKeyMap;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Lays out a keyboard XML of the app the way Keyboard.java does, for the keys the gesture
 * decoder looks at: rows top to bottom, keys left to right with their gaps, widths in %p of
 * the keyboard width. Extension rows (the digit row) and the bottom rows of other modes than
 * mode_normal are skipped.
 */
final class LayoutXml {
    static final String NS = "http://schemas.android.com/apk/res-auto";

    final List<Integer> codes = new ArrayList<>();
    final List<String> popups = new ArrayList<>();
    final List<String> labels = new ArrayList<>();
    final List<int[]> rects = new ArrayList<>();

    static LayoutXml load(File res, String lang, String layout, int width, int keyHeight)
            throws Exception {
        final String dir = "en".equals(lang) ? "xml" : "xml-" + lang;
        File xml = new File(res, dir + "/kbd_" + layout + ".xml");
        if (!xml.exists()) xml = new File(res, "xml/kbd_" + layout + ".xml");
        Map<String, String> strings = new HashMap<>();
        readStrings(new File(res, "values"), strings);
        readStrings(new File(res, "values-" + lang), strings);
        LayoutXml l = new LayoutXml();
        l.parse(xml, strings, width, keyHeight);
        return l;
    }

    private static void readStrings(File dir, Map<String, String> out) throws Exception {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".xml"));
        if (files == null) return;
        for (File f : files) {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f);
            NodeList list = doc.getElementsByTagName("string");
            for (int i = 0; i < list.getLength(); i++) {
                Element e = (Element) list.item(i);
                out.put(e.getAttribute("name"), unescape(e.getTextContent()));
            }
        }
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                c = s.charAt(++i);
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static float size(String value, float parent, float def) {
        if (value == null || value.isEmpty()) return def;
        if (value.endsWith("%p")) {
            return parent * Float.parseFloat(value.substring(0, value.length() - 2)) / 100f;
        }
        if (value.endsWith("px")) return Float.parseFloat(value.substring(0, value.length() - 2));
        return 0;
    }

    private static String attr(Element e, String name) {
        String v = e.getAttributeNS(NS, name);
        return v.isEmpty() ? null : v;
    }

    private void parse(File xml, Map<String, String> strings, int width, int keyHeight)
            throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Document doc = f.newDocumentBuilder().parse(xml);
        Element root = doc.getDocumentElement();
        final float defWidth = size(attr(root, "keyWidth"), width, width / 10f);
        final float defGap = size(attr(root, "horizontalGap"), width, 0);
        int y = 0;
        NodeList rows = root.getChildNodes();
        for (int r = 0; r < rows.getLength(); r++) {
            Node node = rows.item(r);
            if (!(node instanceof Element) || !"Row".equals(node.getNodeName())) continue;
            Element row = (Element) node;
            if ("true".equals(attr(row, "extension"))) continue;
            String mode = attr(row, "keyboardMode");
            if (mode != null && !mode.endsWith("mode_normal")) continue;
            final float rowWidth = size(attr(row, "keyWidth"), width, defWidth);
            final float rowGap = size(attr(row, "horizontalGap"), width, defGap);
            float x = 0;
            NodeList keys = row.getElementsByTagName("Key");
            for (int k = 0; k < keys.getLength(); k++) {
                Element key = (Element) keys.item(k);
                final float w = size(attr(key, "keyWidth"), width, rowWidth);
                x += size(attr(key, "horizontalGap"), width, rowGap);
                String label = attr(key, "keyLabel");
                if (label != null && label.startsWith("@string/")) {
                    label = strings.get(label.substring(8));
                } else if (label != null) {
                    label = unescape(label);
                }
                String popup = attr(key, "popupCharacters");
                if (popup != null && popup.startsWith("@string/")) {
                    popup = strings.get(popup.substring(8));
                } else if (popup != null) {
                    popup = unescape(popup);
                }
                String codesAttr = attr(key, "codes");
                int code = 0;
                if (codesAttr != null) {
                    if (!codesAttr.startsWith("@")) {
                        code = Integer.parseInt(codesAttr.split(",")[0].trim());
                    }
                } else if (label != null && label.codePointCount(0, label.length()) == 1) {
                    code = label.codePointAt(0);
                }
                final boolean modifier = "true".equals(attr(key, "isModifier"));
                if (!modifier && attr(key, "keyOutputText") == null) {
                    codes.add(code);
                    popups.add(popup);
                    labels.add(label);
                    rects.add(new int[] { Math.round(x), y, Math.round(w), keyHeight });
                }
                x += w;
            }
            y += keyHeight;
        }
    }

    GestureKeyMap keyMap() {
        final int n = codes.size();
        int[] c = new int[n], x = new int[n], y = new int[n], w = new int[n], h = new int[n];
        String[] p = new String[n];
        for (int i = 0; i < n; i++) {
            c[i] = codes.get(i);
            p[i] = popups.get(i);
            int[] r = rects.get(i);
            x[i] = r[0];
            y[i] = r[1];
            w[i] = r[2];
            h[i] = r[3];
        }
        return new GestureKeyMap(c, p, x, y, w, h);
    }
}
