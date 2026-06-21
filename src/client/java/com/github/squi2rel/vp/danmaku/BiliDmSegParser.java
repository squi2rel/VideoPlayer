package com.github.squi2rel.vp.danmaku;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class BiliDmSegParser {
    private BiliDmSegParser() {
    }

    static List<DanmakuEntry> parseProtobuf(byte[] data) {
        ProtoReader reader = new ProtoReader(data);
        ArrayList<DanmakuEntry> entries = new ArrayList<>();
        while (reader.available()) {
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 2) {
                entries.add(parseElem(reader.readBytes()));
            } else {
                reader.skip(wire);
            }
        }
        entries.removeIf(entry -> entry == null || !entry.renderable());
        return entries;
    }

    static List<DanmakuEntry> parseXml(byte[] data) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(new String(data, StandardCharsets.UTF_8))));
        NodeList nodes = document.getElementsByTagName("d");
        ArrayList<DanmakuEntry> entries = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) continue;
            String[] params = element.getAttribute("p").split(",");
            if (params.length < 4) continue;
            long progressMs = Math.max(0, Math.round(Double.parseDouble(params[0]) * 1000.0));
            int mode = parseInt(params[1], 1);
            int fontSize = parseInt(params[2], 25);
            int color = parseInt(params[3], 0xFFFFFF);
            String idStr = params.length >= 8 ? params[7] : "";
            DanmakuEntry entry = new DanmakuEntry(0, idStr, progressMs, mode, fontSize, color, element.getTextContent(), 0);
            if (entry.renderable()) entries.add(entry);
        }
        return entries;
    }

    private static DanmakuEntry parseElem(byte[] data) {
        ProtoReader reader = new ProtoReader(data);
        long id = 0;
        String idStr = "";
        long progress = 0;
        int mode = 1;
        int fontSize = 25;
        int color = 0xFFFFFF;
        String content = "";
        int pool = 0;
        while (reader.available()) {
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            switch (field) {
                case 1 -> id = reader.readVarint();
                case 2 -> progress = reader.readVarint();
                case 3 -> mode = (int) reader.readVarint();
                case 4 -> fontSize = (int) reader.readVarint();
                case 5 -> color = (int) reader.readVarint();
                case 7 -> content = new String(reader.readBytes(), StandardCharsets.UTF_8);
                case 11 -> pool = (int) reader.readVarint();
                case 12 -> idStr = new String(reader.readBytes(), StandardCharsets.UTF_8);
                default -> reader.skip(wire);
            }
        }
        return new DanmakuEntry(id, idStr, progress, mode, fontSize, color, content, pool);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static final class ProtoReader {
        private final byte[] data;
        private int offset;

        private ProtoReader(byte[] data) {
            this.data = data == null ? new byte[0] : data;
        }

        boolean available() {
            return offset < data.length;
        }

        int readTag() {
            return (int) readVarint();
        }

        long readVarint() {
            long result = 0;
            int shift = 0;
            while (offset < data.length && shift < 64) {
                int value = data[offset++] & 0xFF;
                result |= (long) (value & 0x7F) << shift;
                if ((value & 0x80) == 0) return result;
                shift += 7;
            }
            return result;
        }

        byte[] readBytes() {
            int length = (int) readVarint();
            if (length <= 0) return new byte[0];
            int end = Math.min(data.length, offset + length);
            byte[] result = new byte[end - offset];
            System.arraycopy(data, offset, result, 0, result.length);
            offset = end;
            return result;
        }

        void skip(int wire) {
            switch (wire) {
                case 0 -> readVarint();
                case 1 -> offset = Math.min(data.length, offset + 8);
                case 2 -> {
                    int length = (int) readVarint();
                    offset = Math.min(data.length, offset + Math.max(0, length));
                }
                case 5 -> offset = Math.min(data.length, offset + 4);
                default -> offset = data.length;
            }
        }
    }
}
