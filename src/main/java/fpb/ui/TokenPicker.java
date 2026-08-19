/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui;

import fpb.io.ImageSource;
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

/** Shows real file/series tokens and lets the user map them to metadata fields. */
public final class TokenPicker extends JPanel {

    private static final String GROUP = "Group";
    private static final String SUBJECT = "Subject";
    private static final String SECTION = "Section";
    private static final String IGNORE = "--";

    private final JComboBox<String> separatorChoice;
    private final JLabel splitLabel;
    private final JPanel tokensPanel;
    private final Map<Integer, JComboBox<String>> fieldChoices =
            new LinkedHashMap<Integer, JComboBox<String>>();
    private ImageSource sampleSource;
    private Runnable changeListener;
    private boolean rebuilding;

    public TokenPicker() {
        super(new BorderLayout(8, 6));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        top.setOpaque(false);
        splitLabel = new JLabel("Split filename on");
        top.add(splitLabel);
        separatorChoice = new FitComboBox<String>(new String[] { "_", "-", ".", "space" });
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
        setSampleSource(file == null ? null : ImageSource.file(file), strategy);
    }

    public void setSampleSource(ImageSource source, TokenStrategy strategy) {
        sampleSource = source;
        rebuilding = true;
        try {
            if (strategy != null) separatorChoice.setSelectedItem(labelFor(strategy.separator()));
            splitLabel.setText(isSeriesSample()
                    ? "Split individual series name on"
                    : "Split filename on");
            Map<Integer, TokenStrategy.Field> initial = strategy == null
                    || (isSeriesSample() && !strategy.splitsSeriesLabels())
                    ? defaultAssignment() : strategy.assignment();
            rebuildTokenChoices(initial);
        } finally {
            rebuilding = false;
        }
    }

    public TokenStrategy strategy() {
        return isSeriesSample()
                ? TokenStrategy.forSeriesLabels(separator(), assignment())
                : new TokenStrategy(separator(), assignment());
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

    public String sampleTextForTest() {
        return sampleText();
    }

    public String splitLabelForTest() {
        return splitLabel.getText();
    }

    public boolean groupChoiceAvailableForTest(int index) {
        JComboBox<String> choice = fieldChoices.get(Integer.valueOf(index));
        if (choice == null) return false;
        for (int i = 0; i < choice.getItemCount(); i++) {
            if (GROUP.equals(choice.getItemAt(i))) return true;
        }
        return false;
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
            JComboBox<String> fieldChoice = new FitComboBox<String>(
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
        return splitTokens(sampleText(), separator());
    }

    private Map<Integer, TokenStrategy.Field> defaultAssignment() {
        Map<Integer, TokenStrategy.Field> assignment =
                new LinkedHashMap<Integer, TokenStrategy.Field>();
        if (isSeriesSample()) {
            return TokenStrategy.guessSeriesAssignment(
                    java.util.Collections.singletonList(sampleText()), separator());
        } else {
            assignment.put(Integer.valueOf(0), TokenStrategy.Field.GROUP);
            assignment.put(Integer.valueOf(1), TokenStrategy.Field.SUBJECT);
        }
        return assignment;
    }

    private boolean isSeriesSample() {
        return sampleSource != null && sampleSource.isSeries();
    }

    private String sampleText() {
        if (sampleSource == null) return "";
        return isSeriesSample() ? sampleSource.seriesLabel()
                : basenameWithoutExtension(sampleSource.file());
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
