/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui;

import fpb.meta.TokenStrategy;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** Shows real filename tokens and lets the user map each token to a metadata field. */
public final class TokenPicker extends JPanel {

    private static final String GROUP = "Group";
    private static final String SUBJECT = "Subject";
    private static final String SECTION = "Section";
    private static final String IGNORE = "--";

    private final JComboBox<String> separatorChoice;
    private final JPanel tokensPanel;
    private final Map<Integer, JComboBox<String>> fieldChoices =
            new LinkedHashMap<Integer, JComboBox<String>>();
    private File sampleFile;
    private Runnable changeListener;
    private boolean rebuilding;

    public TokenPicker() {
        super(new BorderLayout(8, 6));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        top.setOpaque(false);
        top.add(new JLabel("Split filename on"));
        separatorChoice = new JComboBox<String>(new String[] { "_", "-", ".", "space" });
        separatorChoice.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (!rebuilding) {
                    rebuildTokenChoices(defaultAssignment());
                    fireChanged();
                }
            }
        });
        top.add(separatorChoice);
        add(top, BorderLayout.NORTH);

        tokensPanel = new JPanel();
        tokensPanel.setOpaque(false);
        tokensPanel.setLayout(new BoxLayout(tokensPanel, BoxLayout.X_AXIS));
        add(tokensPanel, BorderLayout.CENTER);
    }

    public void setChangeListener(Runnable listener) {
        changeListener = listener;
    }

    public void setSampleFile(File file, TokenStrategy strategy) {
        sampleFile = file == null ? null : file.getAbsoluteFile();
        rebuilding = true;
        try {
            if (strategy != null) separatorChoice.setSelectedItem(labelFor(strategy.separator()));
            rebuildTokenChoices(strategy == null ? defaultAssignment() : strategy.assignment());
        } finally {
            rebuilding = false;
        }
    }

    public TokenStrategy strategy() {
        return new TokenStrategy(separator(), assignment());
    }

    public Map<Integer, TokenStrategy.Field> assignment() {
        Map<Integer, TokenStrategy.Field> assignment =
                new LinkedHashMap<Integer, TokenStrategy.Field>();
        for (Map.Entry<Integer, JComboBox<String>> entry : fieldChoices.entrySet()) {
            assignment.put(entry.getKey(), fieldFor((String) entry.getValue().getSelectedItem()));
        }
        return assignment;
    }

    public char separator() {
        Object selected = separatorChoice.getSelectedItem();
        String label = selected == null ? "_" : selected.toString();
        return "space".equals(label) ? ' ' : label.charAt(0);
    }

    public void setTokenField(int index, TokenStrategy.Field field) {
        JComboBox<String> choice = fieldChoices.get(Integer.valueOf(index));
        if (choice != null) choice.setSelectedItem(labelFor(field));
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        separatorChoice.setEnabled(enabled);
        for (JComboBox<String> choice : fieldChoices.values()) choice.setEnabled(enabled);
    }

    private void rebuildTokenChoices(Map<Integer, TokenStrategy.Field> assignment) {
        tokensPanel.removeAll();
        fieldChoices.clear();
        String[] tokens = tokensForSample();
        for (int i = 0; i < tokens.length; i++) {
            JPanel tokenPanel = new JPanel();
            tokenPanel.setOpaque(false);
            tokenPanel.setLayout(new BoxLayout(tokenPanel, BoxLayout.Y_AXIS));
            tokenPanel.add(new JLabel(tokens[i]));
            JComboBox<String> fieldChoice = new JComboBox<String>(
                    new String[] { GROUP, SUBJECT, SECTION, IGNORE });
            TokenStrategy.Field field = assignment.get(Integer.valueOf(i));
            fieldChoice.setSelectedItem(labelFor(field == null
                    ? TokenStrategy.Field.IGNORE
                    : field));
            fieldChoice.addActionListener(new java.awt.event.ActionListener() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent event) {
                    if (!rebuilding) fireChanged();
                }
            });
            fieldChoices.put(Integer.valueOf(i), fieldChoice);
            tokenPanel.add(fieldChoice);
            tokensPanel.add(tokenPanel);
            if (i < tokens.length - 1) tokensPanel.add(new JLabel("  " + separator() + "  "));
        }
        tokensPanel.revalidate();
        tokensPanel.repaint();
    }

    private String[] tokensForSample() {
        if (sampleFile == null) return new String[] { "" };
        return splitTokens(basenameWithoutExtension(sampleFile), separator());
    }

    private Map<Integer, TokenStrategy.Field> defaultAssignment() {
        Map<Integer, TokenStrategy.Field> assignment =
                new LinkedHashMap<Integer, TokenStrategy.Field>();
        assignment.put(Integer.valueOf(0), TokenStrategy.Field.GROUP);
        assignment.put(Integer.valueOf(1), TokenStrategy.Field.SUBJECT);
        return assignment;
    }

    private void fireChanged() {
        if (changeListener != null) changeListener.run();
    }

    private static TokenStrategy.Field fieldFor(String label) {
        if (GROUP.equals(label)) return TokenStrategy.Field.GROUP;
        if (SUBJECT.equals(label)) return TokenStrategy.Field.SUBJECT;
        if (SECTION.equals(label)) return TokenStrategy.Field.SECTION;
        return TokenStrategy.Field.IGNORE;
    }

    private static String labelFor(TokenStrategy.Field field) {
        if (field == TokenStrategy.Field.GROUP) return GROUP;
        if (field == TokenStrategy.Field.SUBJECT) return SUBJECT;
        if (field == TokenStrategy.Field.SECTION) return SECTION;
        return IGNORE;
    }

    private static String labelFor(char separator) {
        return separator == ' ' ? "space" : String.valueOf(separator);
    }

    private static String basenameWithoutExtension(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static String[] splitTokens(String value, char separator) {
        String clean = clean(value);
        java.util.List<String> tokens = new java.util.ArrayList<String>();
        int start = 0;
        for (int i = 0; i < clean.length(); i++) {
            if (clean.charAt(i) == separator) {
                tokens.add(clean(clean.substring(start, i)));
                start = i + 1;
            }
        }
        tokens.add(clean(clean.substring(start)));
        return tokens.toArray(new String[tokens.size()]);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
