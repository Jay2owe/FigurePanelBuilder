/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui;

import fpb.meta.LabelStrategy;
import fpb.meta.MetadataTable;
import fpb.meta.MetadataTableIO;
import fpb.meta.RegexStrategy;
import fpb.meta.SubfolderStrategy;
import fpb.meta.TokenStrategy;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;

/** First wizard step: choose images and label group/subject/section metadata. */
public final class Step1Images implements WizardStep {

    private static final List<String> IMAGE_EXTENSIONS = Collections.unmodifiableList(
            Arrays.asList("tif", "tiff", "png", "jpg", "jpeg", "gif", "bmp",
                    "lif", "czi", "nd2", "oib", "oif", "lsm"));

    private final FPBWizard.Context context;
    private final Runnable quickGridAction;
    private final JPanel panel;
    private final JTextField folderField;
    private final ToggleSwitch recursiveToggle;
    private final JRadioButton filenameStrategy;
    private final JRadioButton subfolderStrategy;
    private final JRadioButton csvStrategy;
    private final TokenPicker tokenPicker;
    private final MetadataTablePanel tablePanel;
    private final JLabel summaryLabel;
    private boolean loading;

    public Step1Images(FPBWizard.Context context, Runnable quickGridAction) {
        this.context = context;
        this.quickGridAction = quickGridAction;
        panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));

        JPanel top = new JPanel();
        top.setLayout(new javax.swing.BoxLayout(top, javax.swing.BoxLayout.Y_AXIS));

        JPanel folderRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        folderRow.add(new JLabel("Folder"));
        folderField = new JTextField(34);
        folderRow.add(folderField);
        JButton browse = new JButton("Browse");
        browse.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                browseForFolder();
            }
        });
        folderRow.add(browse);
        folderRow.add(new JLabel("Include subfolders"));
        recursiveToggle = new ToggleSwitch(false);
        recursiveToggle.addChangeListener(new Runnable() {
            @Override
            public void run() {
                if (!loading && context.folder != null) loadFolder(context.folder, false);
            }
        });
        folderRow.add(recursiveToggle);
        top.add(folderRow);

        JPanel strategyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        strategyRow.add(new JLabel("Get labels from:"));
        filenameStrategy = new JRadioButton("Filename", true);
        subfolderStrategy = new JRadioButton("Subfolder");
        csvStrategy = new JRadioButton("CSV");
        ButtonGroup group = new ButtonGroup();
        group.add(filenameStrategy);
        group.add(subfolderStrategy);
        group.add(csvStrategy);
        strategyRow.add(filenameStrategy);
        strategyRow.add(subfolderStrategy);
        strategyRow.add(csvStrategy);
        JButton advanced = new JButton("Advanced...");
        advanced.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                showAdvancedRegex();
            }
        });
        strategyRow.add(advanced);
        top.add(strategyRow);

        tokenPicker = new TokenPicker();
        tokenPicker.setChangeListener(new Runnable() {
            @Override
            public void run() {
                if (!loading && filenameStrategy.isSelected()) applyFilenameStrategy();
            }
        });
        top.add(tokenPicker);

        filenameStrategy.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                applyFilenameStrategy();
            }
        });
        subfolderStrategy.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                applySubfolderStrategy();
            }
        });
        csvStrategy.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                tokenPicker.setEnabled(false);
            }
        });

        panel.add(top, BorderLayout.NORTH);

        JPanel centre = new JPanel(new BorderLayout(6, 6));
        summaryLabel = new JLabel("Choose a folder to populate the table.");
        centre.add(summaryLabel, BorderLayout.NORTH);
        tablePanel = new MetadataTablePanel();
        tablePanel.setEditListener(new Runnable() {
            @Override
            public void run() {
                context.tableHandEdited = true;
                updateSummary();
            }
        });
        centre.add(tablePanel, BorderLayout.CENTER);
        panel.add(centre, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        JPanel csvButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton importCsv = new JButton("Import CSV");
        importCsv.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                browseForCsvImport();
            }
        });
        JButton exportCsv = new JButton("Export CSV");
        exportCsv.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                browseForCsvExport();
            }
        });
        csvButtons.add(importCsv);
        csvButtons.add(exportCsv);
        bottom.add(csvButtons, BorderLayout.CENTER);

        JButton quickGrid = new JButton("Quick grid");
        quickGrid.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (quickGridAction != null) quickGridAction.run();
            }
        });
        bottom.add(quickGrid, BorderLayout.WEST);
        panel.add(bottom, BorderLayout.SOUTH);
    }

    @Override
    public String title() {
        return "Images";
    }

    @Override
    public String nextTitle() {
        return "Channels";
    }

    @Override
    public JPanel component() {
        return panel;
    }

    @Override
    public void onShow() {
        if (context.metadataTable != null) {
            tablePanel.setMetadataTable(context.metadataTable);
            updateSummary();
        }
    }

    @Override
    public boolean canAdvance() {
        return context.metadataTable != null && context.metadataTable.fileCount() > 0;
    }

    public void chooseFolder(File folder) {
        loadFolder(folder, true);
    }

    public MetadataTable metadataTable() {
        return context.metadataTable;
    }

    public String summaryText() {
        return summaryLabel.getText();
    }

    public String selectedStrategyName() {
        if (subfolderStrategy.isSelected()) return "Subfolder";
        if (csvStrategy.isSelected()) return "CSV";
        return "Filename";
    }

    public TokenPicker tokenPicker() {
        return tokenPicker;
    }

    public MetadataTablePanel tablePanel() {
        return tablePanel;
    }

    public void importCsv(File csvFile) throws IOException {
        if (context.metadataTable == null) {
            throw new IOException("Choose an image folder before importing CSV metadata.");
        }
        MetadataTableIO.importCsv(context.metadataTable, csvFile);
        csvStrategy.setSelected(true);
        tokenPicker.setEnabled(false);
        context.tableHandEdited = false;
        tablePanel.setMetadataTable(context.metadataTable);
        updateSummary();
    }

    public void exportCsv(File csvFile) throws IOException {
        if (context.metadataTable == null) {
            throw new IOException("Choose an image folder before exporting CSV metadata.");
        }
        MetadataTableIO.exportCsv(context.metadataTable, csvFile);
    }

    private void browseForFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (context.folder != null) chooser.setCurrentDirectory(context.folder);
        if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
            chooseFolder(chooser.getSelectedFile());
        }
    }

    private void browseForCsvImport() {
        JFileChooser chooser = csvChooser();
        if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
            try {
                importCsv(chooser.getSelectedFile());
            } catch (IOException failure) {
                showMessage(failure.getMessage());
            }
        }
    }

    private void browseForCsvExport() {
        JFileChooser chooser = csvChooser();
        if (chooser.showSaveDialog(panel) == JFileChooser.APPROVE_OPTION) {
            try {
                exportCsv(chooser.getSelectedFile());
            } catch (IOException failure) {
                showMessage(failure.getMessage());
            }
        }
    }

    private static JFileChooser csvChooser() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        return chooser;
    }

    private void loadFolder(File folder, boolean allowRecursiveFallback) {
        if (folder == null) return;
        loading = true;
        try {
            File root = folder.getAbsoluteFile();
            context.folder = root;
            folderField.setText(root.getAbsolutePath());
            context.recursive = recursiveToggle.isSelected();
            List<File> files = discoverImages(root, context.recursive);
            if (files.isEmpty() && allowRecursiveFallback && !context.recursive) {
                List<File> recursiveFiles = discoverImages(root, true);
                if (!recursiveFiles.isEmpty()) {
                    recursiveToggle.setSelected(true);
                    context.recursive = true;
                    files = recursiveFiles;
                }
            }
            if (files.isEmpty()) {
                context.metadataTable = MetadataTable.empty(root, files);
                tablePanel.setMetadataTable(context.metadataTable);
                updateSummary();
                return;
            }
            LabelStrategy strategy = MetadataTable.suggest(root, files);
            context.metadataTable = MetadataTable.fromFiles(root, files, strategy);
            selectStrategy(strategy);
            tablePanel.setMetadataTable(context.metadataTable);
            updateSummary();
        } catch (IOException failure) {
            showMessage(failure.getMessage());
        } finally {
            loading = false;
        }
    }

    private void selectStrategy(LabelStrategy strategy) {
        loading = true;
        try {
            if (strategy instanceof SubfolderStrategy) {
                subfolderStrategy.setSelected(true);
                tokenPicker.setEnabled(false);
            } else {
                filenameStrategy.setSelected(true);
                tokenPicker.setEnabled(true);
                File sample = context.metadataTable == null
                        || context.metadataTable.rows().isEmpty()
                        ? null
                        : context.metadataTable.rows().get(0).file;
                tokenPicker.setSampleFile(sample, strategy instanceof TokenStrategy
                        ? (TokenStrategy) strategy
                        : null);
            }
        } finally {
            loading = false;
        }
    }

    private void applyFilenameStrategy() {
        tokenPicker.setEnabled(true);
        applyStrategy(tokenPicker.strategy());
    }

    private void applySubfolderStrategy() {
        tokenPicker.setEnabled(false);
        applyStrategy(new SubfolderStrategy());
    }

    private void applyStrategy(LabelStrategy strategy) {
        if (context.metadataTable == null) return;
        strategy.apply(context.metadataTable);
        tablePanel.setMetadataTable(context.metadataTable);
        updateSummary();
    }

    private void showAdvancedRegex() {
        if (context.metadataTable == null) {
            showMessage("Choose an image folder before applying a regular expression.");
            return;
        }
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        JTextField regex = new JTextField("(.+?)_(.+?)(?:_(.+?))?\\..+");
        JTextField groupCapture = new JTextField("1");
        JTextField subjectCapture = new JTextField("2");
        JTextField sectionCapture = new JTextField("3");
        form.add(new JLabel("Regular expression"));
        form.add(regex);
        form.add(new JLabel("Group capture"));
        form.add(groupCapture);
        form.add(new JLabel("Subject capture"));
        form.add(subjectCapture);
        form.add(new JLabel("Section capture"));
        form.add(sectionCapture);
        int result = JOptionPane.showConfirmDialog(panel, form, "Advanced metadata",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            try {
                RegexStrategy strategy = new RegexStrategy(regex.getText(),
                        Integer.parseInt(groupCapture.getText().trim()),
                        Integer.parseInt(subjectCapture.getText().trim()),
                        Integer.parseInt(sectionCapture.getText().trim()));
                applyStrategy(strategy);
                tokenPicker.setEnabled(false);
            } catch (RuntimeException failure) {
                showMessage(failure.getMessage());
            }
        }
    }

    private void updateSummary() {
        if (context.metadataTable == null) {
            summaryLabel.setText("Choose a folder to populate the table.");
        } else {
            summaryLabel.setText(context.metadataTable.summary());
        }
    }

    private void showMessage(String message) {
        JOptionPane.showMessageDialog(panel, message, "Figure Panel Builder",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private static List<File> discoverImages(File folder, boolean recursive) throws IOException {
        if (folder == null || !folder.isDirectory()) {
            throw new IOException("Folder does not exist: " + folder);
        }
        List<File> files = new ArrayList<File>();
        collectImages(folder.getAbsoluteFile(), recursive, files);
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                int byIgnoreCase = left.getAbsolutePath().compareToIgnoreCase(
                        right.getAbsolutePath());
                if (byIgnoreCase != 0) return byIgnoreCase;
                return left.getAbsolutePath().compareTo(right.getAbsolutePath());
            }
        });
        return files;
    }

    private static void collectImages(File folder, boolean recursive, List<File> files) {
        File[] children = folder.listFiles();
        if (children == null) return;
        Arrays.sort(children, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
        for (int i = 0; i < children.length; i++) {
            File child = children[i];
            if (child.isDirectory()) {
                if (recursive) collectImages(child, true, files);
            } else if (isSupportedImage(child)) {
                files.add(child.getAbsoluteFile());
            }
        }
    }

    private static boolean isSupportedImage(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        return IMAGE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
