/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui.layout;

import fpb.figure.PanelConfig;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Window;

/** Four-slider spacing editor for group rows, group columns, inner gaps and margin. */
public final class LayoutEditor extends JDialog {

    private int rowGap;
    private int groupGap;
    private int innerGap;
    private int margin;
    private final PanelConfig baseConfig;
    private PanelConfig result;
    private Listener previewListener;

    private LayoutEditor(Window owner, PanelConfig config) {
        super(owner, "Edit spacing", Dialog.ModalityType.APPLICATION_MODAL);
        this.baseConfig = config;
        rowGap = config.rowGapPx();
        groupGap = config.groupGapPx();
        innerGap = config.innerColGapPx();
        margin = config.marginPx();
        buildUi(config);
        pack();
        setLocationRelativeTo(owner);
    }

    public static PanelConfig edit(Window owner, PanelConfig config,
            Listener previewListener) {
        if (config == null || GraphicsEnvironment.isHeadless()) return config;
        LayoutEditor editor = new LayoutEditor(owner, config);
        editor.previewListener = previewListener;
        editor.setVisible(true);
        return editor.result;
    }

    public static PanelConfig applySpacing(PanelConfig config, int rowGap,
            int groupGap, int innerGap, int margin) {
        if (config == null) throw new IllegalArgumentException("config is required");
        return config.toBuilder()
                .rowGapPx(rowGap)
                .groupGapPx(groupGap)
                .innerColGapPx(innerGap)
                .marginPx(margin)
                .build();
    }

    private void buildUi(PanelConfig base) {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel sliders = new JPanel();
        sliders.setLayout(new javax.swing.BoxLayout(sliders,
                javax.swing.BoxLayout.Y_AXIS));
        sliders.add(slider("Row gap", rowGap, value -> {
            rowGap = value;
            firePreview();
        }));
        sliders.add(slider("Column gap", groupGap, value -> {
            groupGap = value;
            firePreview();
        }));
        sliders.add(slider("Inner gap", innerGap, value -> {
            innerGap = value;
            firePreview();
        }));
        sliders.add(slider("Margin", margin, value -> {
            margin = value;
            firePreview();
        }));
        root.add(sliders, BorderLayout.CENTER);

        JLabel hint = new JLabel("Spacing changes apply uniformly to the figure.");
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
        root.add(hint, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> {
            result = null;
            dispose();
        });
        JButton ok = new JButton("OK");
        ok.addActionListener(e -> {
            result = applySpacing(base, rowGap, groupGap, innerGap, margin);
            dispose();
        });
        buttons.add(cancel);
        buttons.add(ok);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
    }

    private JPanel slider(String label, int value, IntConsumer consumer) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JLabel name = new JLabel(label);
        name.setPreferredSize(new Dimension(82, 18));
        JLabel valueLabel = new JLabel(String.valueOf(value));
        valueLabel.setPreferredSize(new Dimension(34, 18));
        JSlider slider = new JSlider(0, Math.max(80, value), Math.max(0, value));
        slider.setPreferredSize(new Dimension(180, 22));
        slider.addChangeListener(e -> {
            int v = slider.getValue();
            valueLabel.setText(String.valueOf(v));
            consumer.accept(v);
        });
        row.add(name);
        row.add(slider);
        row.add(valueLabel);
        return row;
    }

    private void firePreview() {
        if (previewListener != null) {
            previewListener.spacingChanged(applySpacing(baseConfig, rowGap,
                    groupGap, innerGap, margin));
        }
    }

    private interface IntConsumer {
        void accept(int value);
    }

    public interface Listener {
        void spacingChanged(PanelConfig config);
    }
}
