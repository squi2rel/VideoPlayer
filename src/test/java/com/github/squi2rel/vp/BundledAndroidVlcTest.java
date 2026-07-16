package com.github.squi2rel.vp;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundledAndroidVlcTest {
    @Test
    void bundlesVerifiedArm64RuntimeWithJvmBridge() throws Exception {
        byte[] zip;
        try (InputStream input = BundledAndroidVlcTest.class.getResourceAsStream(NativePackageManager.BUNDLED_ANDROID_VLC_RESOURCE)) {
            assertNotNull(input);
            zip = input.readAllBytes();
        }

        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(zip));
        assertEquals(NativePackageManager.BUNDLED_ANDROID_VLC_SHA256, digest);

        Set<String> entries = new HashSet<>();
        Map<String, byte[]> libraries = new HashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.add(entry.getName());
                    if (entry.getName().endsWith(".so")) libraries.put(entry.getName(), input.readAllBytes());
                }
            }
        }
        assertTrue(entries.contains("libvlc.so"));
        assertTrue(entries.contains("libvlcjni.so"));
        assertTrue(entries.contains("libvlc_jvm_bridge.so"));
        assertTrue(entries.contains("libc++_shared.so"));

        Set<String> androidSystemLibraries = Set.of(
                "libEGL.so", "libGLESv2.so", "libandroid.so", "libc.so", "libdl.so",
                "liblog.so", "libm.so", "libmediandk.so"
        );
        for (Map.Entry<String, byte[]> library : libraries.entrySet()) {
            ElfInfo elf = readElf(library.getValue());
            assertEquals(2, elf.elfClass());
            assertEquals(1, elf.endian());
            assertEquals(183, elf.machine());
            for (String needed : elf.needed()) {
                assertTrue(libraries.containsKey(needed) || androidSystemLibraries.contains(needed),
                        () -> library.getKey() + " requires unavailable " + needed);
            }
        }
        assertTrue(contains(libraries.get("libvlc.so"), "vlc_static_modules".getBytes(StandardCharsets.US_ASCII)));
    }

    private static ElfInfo readElf(byte[] data) {
        assertTrue(data.length >= 64);
        assertEquals(0x7f, data[0] & 0xff);
        assertEquals('E', data[1]);
        assertEquals('L', data[2]);
        assertEquals('F', data[3]);
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int elfClass = data[4] & 0xff;
        int endian = data[5] & 0xff;
        int machine = buffer.getShort(18) & 0xffff;
        long programOffset = buffer.getLong(32);
        int programEntrySize = buffer.getShort(54) & 0xffff;
        int programCount = buffer.getShort(56) & 0xffff;
        ArrayList<LoadSegment> loads = new ArrayList<>();
        long dynamicOffset = -1;
        long dynamicSize = 0;
        for (int i = 0; i < programCount; i++) {
            int offset = Math.toIntExact(programOffset + (long) i * programEntrySize);
            int type = buffer.getInt(offset);
            long fileOffset = buffer.getLong(offset + 8);
            long virtualAddress = buffer.getLong(offset + 16);
            long fileSize = buffer.getLong(offset + 32);
            if (type == 1) loads.add(new LoadSegment(virtualAddress, virtualAddress + fileSize, fileOffset));
            if (type == 2) {
                dynamicOffset = fileOffset;
                dynamicSize = fileSize;
            }
        }
        long stringTableAddress = -1;
        ArrayList<Long> neededOffsets = new ArrayList<>();
        if (dynamicOffset >= 0) {
            for (long offset = dynamicOffset; offset + 16 <= dynamicOffset + dynamicSize; offset += 16) {
                long tag = buffer.getLong(Math.toIntExact(offset));
                long value = buffer.getLong(Math.toIntExact(offset + 8));
                if (tag == 0) break;
                if (tag == 1) neededOffsets.add(value);
                if (tag == 5) stringTableAddress = value;
            }
        }
        long stringTableOffset = -1;
        for (LoadSegment load : loads) {
            if (stringTableAddress >= load.start() && stringTableAddress < load.end()) {
                stringTableOffset = load.fileOffset() + stringTableAddress - load.start();
                break;
            }
        }
        ArrayList<String> needed = new ArrayList<>();
        if (stringTableOffset >= 0) {
            for (long neededOffset : neededOffsets) {
                int start = Math.toIntExact(stringTableOffset + neededOffset);
                int end = start;
                while (end < data.length && data[end] != 0) end++;
                needed.add(new String(data, start, end - start, StandardCharsets.UTF_8));
            }
        }
        return new ElfInfo(elfClass, endian, machine, List.copyOf(needed));
    }

    private static boolean contains(byte[] data, byte[] needle) {
        if (data == null || needle.length == 0 || data.length < needle.length) return false;
        for (int i = 0; i <= data.length - needle.length; i++) {
            int j = 0;
            while (j < needle.length && data[i + j] == needle[j]) j++;
            if (j == needle.length) return true;
        }
        return false;
    }

    private record LoadSegment(long start, long end, long fileOffset) {
    }

    private record ElfInfo(int elfClass, int endian, int machine, List<String> needed) {
    }
}
