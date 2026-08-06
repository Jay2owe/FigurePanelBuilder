/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.figure;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic, versioned macro-option encoding for every panel-layout setting. */
public final class PanelConfigCodec {

    private static final int VERSION = 7;

    private PanelConfigCodec() {}

    public static String encode(PanelConfig config) {
        if (config == null) return "";
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            out.writeBoolean(config.createOverviewPanel());
            out.writeBoolean(config.annotateOverviewPanel());
            out.writeBoolean(config.annotateIndividualPanels());
            out.writeUTF(config.groupRowsBy().name());
            writeStrings(out, config.channelOrder());
            out.writeInt(config.cellSizePx());
            out.writeBoolean(config.scaleBarEnabled());
            out.writeDouble(config.scaleBarLengthUm());
            out.writeInt(config.scaleBarThicknessPx());
            out.writeUTF(config.scaleBarPosition().name());
            out.writeInt(config.annotationColor().getRGB());
            out.writeUTF(config.labelMode().name());
            out.writeUTF(config.customLabelTemplate());
            out.writeInt(config.labelFontSizePx());
            out.writeUTF(config.labelPosition().name());
            out.writeInt(config.marginPx());
            out.writeInt(config.innerColGapPx());
            out.writeInt(config.groupGapPx());
            out.writeInt(config.rowGapPx());
            out.writeInt(config.groupFontSizePx());
            out.writeInt(config.channelFontSizePx());
            out.writeInt(config.rowFontSizePx());
            out.writeUTF(config.channelHeaderOrientation().name());
            out.writeUTF(config.rowLabelOrientation().name());
            out.writeInt(config.channelHeaderGapPx());
            out.writeInt(config.rowLabelGapPx());
            out.writeBoolean(config.groupHeaderVisible());
            out.writeBoolean(config.channelHeaderVisible());
            out.writeBoolean(config.rowLabelVisible());
            out.writeInt(config.outputDpi());
            out.writeInt(config.exportScale());
            out.writeDouble(config.labelFracX());
            out.writeDouble(config.labelFracY());
            out.writeDouble(config.scaleBarFracX());
            out.writeDouble(config.scaleBarFracY());
            out.writeInt(config.groupLayoutRows().size());
            for (List<String> row : config.groupLayoutRows()) writeStrings(out, row);
            writeStringMap(out, config.externalLabelOverrides());
            out.writeBoolean(config.annotationSnapEnabled());
            writeStringMap(out, config.imageOrientations());
            out.writeUTF(config.groupHeaderAlignment().name());
            out.close();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not encode panel configuration.", impossible);
        }
    }

    public static PanelConfig decode(String encoded) {
        if (encoded == null || encoded.trim().isEmpty()) return null;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded.trim());
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            int version = in.readInt();
            if (version < 1 || version > VERSION) {
                throw new IllegalArgumentException("Unsupported panel_config version: "
                        + version);
            }
            PanelConfig.Builder builder = PanelConfig.builder()
                    .createOverviewPanel(in.readBoolean())
                    .annotateOverviewPanel(in.readBoolean())
                    .annotateIndividualPanels(in.readBoolean())
                    .groupRowsBy(PanelConfig.GroupRowsBy.valueOf(in.readUTF()))
                    .channelOrder(readStrings(in))
                    .cellSizePx(in.readInt())
                    .scaleBarEnabled(in.readBoolean())
                    .scaleBarLengthUm(in.readDouble())
                    .scaleBarThicknessPx(in.readInt())
                    .scaleBarPosition(PanelConfig.Position.valueOf(in.readUTF()))
                    .annotationColor(new Color(in.readInt(), true))
                    .labelMode(PanelConfig.LabelMode.valueOf(in.readUTF()))
                    .customLabelTemplate(in.readUTF())
                    .labelFontSizePx(in.readInt())
                    .labelPosition(PanelConfig.Position.valueOf(in.readUTF()))
                    .marginPx(in.readInt())
                    .innerColGapPx(in.readInt())
                    .groupGapPx(in.readInt())
                    .rowGapPx(in.readInt())
                    .groupFontSizePx(in.readInt())
                    .channelFontSizePx(in.readInt());
            if (version >= 2) {
                builder.rowFontSizePx(in.readInt())
                        .channelHeaderOrientation(PanelConfig.TextOrientation
                                .valueOf(in.readUTF()))
                        .rowLabelOrientation(PanelConfig.TextOrientation
                                .valueOf(in.readUTF()))
                        .channelHeaderGapPx(in.readInt())
                        .rowLabelGapPx(in.readInt());
            }
            builder.groupHeaderVisible(in.readBoolean())
                    .channelHeaderVisible(in.readBoolean());
            if (version >= 3) builder.rowLabelVisible(in.readBoolean());
            builder.outputDpi(in.readInt())
                    .exportScale(in.readInt())
                    .labelFracX(in.readDouble())
                    .labelFracY(in.readDouble())
                    .scaleBarFracX(in.readDouble())
                    .scaleBarFracY(in.readDouble());
            int rowCount = in.readInt();
            if (rowCount < 0 || rowCount > 10000) {
                throw new IllegalArgumentException("Invalid panel_config row count.");
            }
            List<List<String>> rows = new ArrayList<List<String>>();
            for (int i = 0; i < rowCount; i++) rows.add(readStrings(in));
            if (version >= 4) builder.externalLabelOverrides(readStringMap(in));
            if (version >= 5) builder.annotationSnapEnabled(in.readBoolean());
            if (version >= 6) builder.imageOrientations(readStringMap(in));
            if (version >= 7) builder.groupHeaderAlignment(
                    PanelConfig.TextAlignment.valueOf(in.readUTF()));
            PanelConfig result = builder.groupLayoutRows(rows).build();
            if (in.read() != -1) {
                throw new IllegalArgumentException("Unexpected trailing panel_config data.");
            }
            return result;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("panel_config is invalid: "
                    + failure.getMessage(), failure);
        } catch (IOException failure) {
            throw new IllegalArgumentException("panel_config is truncated.", failure);
        }
    }

    private static void writeStrings(DataOutputStream out, List<String> values)
            throws IOException {
        out.writeInt(values == null ? 0 : values.size());
        if (values == null) return;
        for (String value : values) out.writeUTF(value == null ? "" : value);
    }

    private static List<String> readStrings(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 10000) {
            throw new IllegalArgumentException("Invalid panel_config list size.");
        }
        List<String> values = new ArrayList<String>();
        for (int i = 0; i < count; i++) values.add(in.readUTF());
        return values;
    }

    private static void writeStringMap(DataOutputStream out,
            Map<String, String> values) throws IOException {
        out.writeInt(values == null ? 0 : values.size());
        if (values == null) return;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            out.writeUTF(entry.getKey() == null ? "" : entry.getKey());
            out.writeUTF(entry.getValue() == null ? "" : entry.getValue());
        }
    }

    private static Map<String, String> readStringMap(DataInputStream in)
            throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 10000) {
            throw new IllegalArgumentException("Invalid panel_config map size.");
        }
        LinkedHashMap<String, String> values =
                new LinkedHashMap<String, String>();
        for (int i = 0; i < count; i++) values.put(in.readUTF(), in.readUTF());
        return values;
    }
}
