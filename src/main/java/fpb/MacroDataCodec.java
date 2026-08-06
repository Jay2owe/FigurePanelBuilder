/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collision-free URL-safe encoding for user-authored macro labels and picks. */
final class MacroDataCodec {

    private static final int VERSION = 1;
    private static final int MAX_ITEMS = 10000;
    private static final int MAX_TEXT_BYTES = 16 * 1024 * 1024;

    private MacroDataCodec() {}

    static String encodeStrings(List<String> values) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            List<String> safe = values == null
                    ? java.util.Collections.<String>emptyList() : values;
            out.writeInt(safe.size());
            for (String value : safe) writeString(out, value);
            out.close();
            return encode(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not encode macro strings.", impossible);
        }
    }

    static String encodeString(String value) {
        return encodeStrings(java.util.Collections.singletonList(
                value == null ? "" : value));
    }

    static List<String> decodeStrings(String encoded) {
        try {
            DataInputStream in = input(encoded);
            requireVersion(in);
            int count = readCount(in);
            List<String> values = new ArrayList<String>(count);
            for (int i = 0; i < count; i++) values.add(readString(in));
            requireEnd(in);
            return values;
        } catch (IOException failure) {
            throw invalid(failure);
        }
    }

    static String decodeString(String encoded) {
        List<String> values = decodeStrings(encoded);
        if (values.size() != 1) {
            throw new IllegalArgumentException(
                    "Encoded macro string must contain exactly one value.");
        }
        return values.get(0);
    }

    static String encodeMap(Map<String, String> values) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            Map<String, String> safe = values == null
                    ? java.util.Collections.<String, String>emptyMap() : values;
            out.writeInt(safe.size());
            for (Map.Entry<String, String> entry : safe.entrySet()) {
                writeString(out, entry.getKey());
                writeString(out, entry.getValue());
            }
            out.close();
            return encode(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not encode macro map.", impossible);
        }
    }

    static Map<String, String> decodeMap(String encoded) {
        try {
            DataInputStream in = input(encoded);
            requireVersion(in);
            int count = readCount(in);
            LinkedHashMap<String, String> values =
                    new LinkedHashMap<String, String>();
            for (int i = 0; i < count; i++) {
                String key = readString(in);
                if (values.containsKey(key)) {
                    throw new IllegalArgumentException("Encoded macro map has a duplicate key.");
                }
                values.put(key, readString(in));
            }
            requireEnd(in);
            return values;
        } catch (IOException failure) {
            throw invalid(failure);
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static DataInputStream input(String encoded) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(
                    encoded == null ? "" : encoded.trim());
            if (bytes.length > MAX_TEXT_BYTES) {
                throw new IllegalArgumentException("Encoded macro data is too large.");
            }
            return new DataInputStream(new ByteArrayInputStream(bytes));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Encoded macro data is invalid: "
                    + failure.getMessage(), failure);
        }
    }

    private static void requireVersion(DataInputStream in) throws IOException {
        int version = in.readInt();
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported macro data version: " + version);
        }
    }

    private static int readCount(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_ITEMS) {
            throw new IllegalArgumentException("Invalid macro item count.");
        }
        return count;
    }

    private static void writeString(DataOutputStream out, String value)
            throws IOException {
        byte[] text = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (text.length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("Macro text value is too large.");
        }
        out.writeInt(text.length);
        out.write(text);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("Invalid macro text length.");
        }
        byte[] text = new byte[length];
        in.readFully(text);
        return new String(text, StandardCharsets.UTF_8);
    }

    private static void requireEnd(DataInputStream in) throws IOException {
        if (in.read() != -1) {
            throw new IllegalArgumentException("Unexpected trailing macro data.");
        }
    }

    private static IllegalArgumentException invalid(IOException failure) {
        return new IllegalArgumentException("Encoded macro data is truncated.", failure);
    }
}
