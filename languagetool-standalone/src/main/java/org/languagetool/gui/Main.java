/* LanguageTool, a natural language style checker 
 * Copyright (C) 2005 Daniel Naber (http://www.danielnaber.de)
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool.gui;

import org.languagetool.AnalyzedSentence;
import org.languagetool.JLanguageTool;
import org.languagetool.Language;
import org.languagetool.language.RuleFilenameException;
import org.languagetool.rules.Rule;
import org.languagetool.server.HTTPServer;
import org.languagetool.server.HTTPServerConfig;
import org.languagetool.server.PortBindingException;
import org.languagetool.tools.JnaTools;
import org.languagetool.tools.LanguageIdentifierTools;
import org.languagetool.tools.StringTools;

import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.*;
import java.io.*;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;
import javax.swing.text.DefaultEditorKit;

/**
 * A simple GUI to check texts with.
 *
 * @author Daniel Naber
 */
public final class Main {

  static final String EXTERNAL_LANGUAGE_SUFFIX = " (ext.)";
  static final String HTML_FONT_START = "<font face='Arial,Helvetica'>";
  static final String HTML_FONT_END = "</font>";
  static final String HTML_GREY_FONT_START = "<font face='Arial,Helvetica' color='#666666'>";

  private static final String TRAY_ICON = "/TrayIcon.png";
  private static final String TRAY_SERVER_ICON = "/TrayIconWithServer.png";
  private static final String TRAY_SMALL_ICON = "/TrayIconSmall.png";
  private static final String TRAY_SMALL_SERVER_ICON = "/TrayIconSmallWithServer.png";
  private static final String TRAY_TOOLTIP = "LanguageTool";

  private static final String CONFIG_FILE = ".languagetool.cfg";
  private static final int WINDOW_WIDTH = 600;
  private static final int WINDOW_HEIGHT = 550;

  private final ResourceBundle messages;

  private JFrame frame;
  private JTextArea textArea;
  private JTextPane resultArea;
  private ResultArea resultAreaHelper;
  private LanguageComboBox languageBox;
  private JCheckBox autoDetectBox;
  private CheckboxMenuItem enableHttpServerItem;

  private HTTPServer httpServer;


  private TrayIcon trayIcon;
  private boolean closeHidesToTray;
  private boolean isInTray;

  private LanguageToolSupport ltSupport;
  private OpenAction openAction;
  private SaveAction saveAction;
  private SaveAsAction saveAsAction;
  private AutoCheckAction autoCheckAction;

  private CheckAction checkAction;
  private File currentFile;
  private UndoRedoSupport undoRedo;
  private long startTime;
  
  private Main() throws IOException {
    LanguageIdentifierTools.addLtProfiles();
    messages = JLanguageTool.getMessageBundle();
  }

  void loadFile() {
    final File file = Tools.openFileDialog(frame, new PlainTextFileFilter());
    if (file == null) {
      // user clicked cancel
      return;
    }
    try {
      final FileInputStream inputStream = new FileInputStream(file);
      try {
        final String fileContents = StringTools.readFile(inputStream);
        textArea.setText(fileContents);
        currentFile = file;
        updateTitle();
      } finally {
        inputStream.close();
      }
    } catch (IOException e) {
      Tools.showError(e);
    }
  }

  private void saveFile(boolean newFile) {
    if (currentFile == null || newFile) {
      final JFileChooser jfc = new JFileChooser();
      jfc.setFileFilter(new PlainTextFileFilter());
      jfc.showSaveDialog(frame);

      File file = jfc.getSelectedFile();
      if (file == null) {
        // user clicked cancel
        return;
      }
      currentFile = file;
      updateTitle();
    }
    BufferedWriter writer = null;
    try {
      writer = new BufferedWriter(new FileWriter(currentFile));
      writer.write(textArea.getText());
    } catch (IOException ex) {
      Tools.showError(ex);
    } finally {
      if (writer != null) {
        try {
          writer.close();
        } catch (IOException ex) {
          Tools.showError(ex);
        }
      }
    }
  }

  void addLanguage() {
    final LanguageManagerDialog lmd = new LanguageManagerDialog(frame, Language.getExternalLanguages());
    lmd.show();
    try {
      Language.reInit(lmd.getLanguages());
    } catch (RuleFilenameException e) {
      Tools.showErrorMessage(e, frame);
    }
    languageBox.populateLanguageBox();
    languageBox.selectLanguage(ltSupport.getLanguageTool().getLanguage());
  }

  void showOptions() {
    final JLanguageTool langTool = ltSupport.getLanguageTool();
    final List<Rule> rules = langTool.getAllRules();
    final ConfigurationDialog configDialog = ltSupport.getCurrentConfigDialog();
    configDialog.show(rules); // this blocks until OK/Cancel is clicked in the dialog
    Configuration config = ltSupport.getConfig();
    config.setDisabledRuleIds(configDialog.getDisabledRuleIds());
    config.setEnabledRuleIds(configDialog.getEnabledRuleIds());
    config.setDisabledCategoryNames(configDialog.getDisabledCategoryNames());
    config.setMotherTongue(configDialog.getMotherTongue());
    config.setRunServer(configDialog.getRunServer());
    config.setUseGUIConfig(configDialog.getUseGUIConfig());
    config.setServerPort(configDialog.getServerPort());
    try { //save config - needed for the server
      config.saveConfiguration(langTool.getLanguage());
    } catch (IOException e) {
      Tools.showError(e);
    }
    // Stop server, start new server if requested:
    stopServer();
    maybeStartServer();
  }

  Component getFrame() {
    return frame;
  }

  private void updateTitle()
  {
    if(currentFile == null)
      frame.setTitle("LanguageTool " + JLanguageTool.VERSION);
    else
      frame.setTitle(currentFile.getName() +" - LanguageTool " + JLanguageTool.VERSION);
  }

  private void createGUI() {
    frame = new JFrame("LanguageTool " + JLanguageTool.VERSION);

    setLookAndFeel();
    openAction = new OpenAction();
    saveAction = new SaveAction();
    saveAsAction = new SaveAsAction();
    checkAction = new CheckAction();
    autoCheckAction = new AutoCheckAction(true);

    frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    frame.addWindowListener(new CloseListener());
    final URL iconUrl = JLanguageTool.getDataBroker().getFromResourceDirAsUrl(TRAY_ICON);
    frame.setIconImage(new ImageIcon(iconUrl).getImage());

    textArea = new JTextArea();
    // TODO: wrong line number is displayed for lines that are wrapped automatically:
    textArea.setLineWrap(true);
    textArea.setWrapStyleWord(true);
    textArea.addKeyListener(new ControlReturnTextCheckingListener());
    resultArea = new JTextPane();
    undoRedo = new UndoRedoSupport(this.textArea);
    frame.setJMenuBar(createMenuBar());

    final JPanel panel = new JPanel();
    panel.setOpaque(false);    // to get rid of the gray background
    panel.setLayout(new GridBagLayout());
    final GridBagConstraints buttonCons = new GridBagConstraints();
    final JPanel insidePanel = new JPanel();
    insidePanel.setOpaque(false);
    insidePanel.setLayout(new GridBagLayout());
    buttonCons.gridx = 0;
    buttonCons.gridy = 0;
    buttonCons.anchor = GridBagConstraints.WEST;
    insidePanel.add(new JLabel(" " + messages.getString("textLanguage") + " "), buttonCons);
    languageBox = new LanguageComboBox(messages, EXTERNAL_LANGUAGE_SUFFIX);
    languageBox.setRenderer(new LanguageComboBoxRenderer(messages, EXTERNAL_LANGUAGE_SUFFIX));

    buttonCons.gridx = 1;
    buttonCons.gridy = 0;
    insidePanel.add(languageBox, buttonCons);
    buttonCons.gridx = 0;
    buttonCons.gridy = 0;
    panel.add(insidePanel);
    buttonCons.gridx = 2;
    buttonCons.gridy = 0;

    autoDetectBox = new JCheckBox(messages.getString("atd"));

    buttonCons.gridx = 1;
    buttonCons.gridy = 1;
    buttonCons.gridwidth = 2;
    buttonCons.anchor = GridBagConstraints.WEST;
    insidePanel.add(autoDetectBox, buttonCons);

    final Container contentPane = frame.getContentPane();
    final GridBagLayout gridLayout = new GridBagLayout();
    contentPane.setLayout(gridLayout);
    final GridBagConstraints cons = new GridBagConstraints();

    cons.gridx = 0;
    cons.gridy = 1;
    cons.fill = GridBagConstraints.HORIZONTAL;
    cons.anchor = GridBagConstraints.FIRST_LINE_START;
    JToolBar toolbar = new JToolBar("Toolbar", JToolBar.HORIZONTAL);
    toolbar.setFloatable(false);
    contentPane.add(toolbar,cons);

    JButton openbutton = new JButton(openAction);
    openbutton.setHideActionText(true);
    openbutton.setFocusable(false);
    toolbar.add(openbutton);

    JButton savebutton = new JButton(saveAction);
    savebutton.setHideActionText(true);
    savebutton.setFocusable(false);
    toolbar.add(savebutton);

    JButton saveasbutton = new JButton(saveAsAction);
    saveasbutton.setHideActionText(true);
    saveasbutton.setFocusable(false);
    toolbar.add(saveasbutton);

    JButton spellbutton = new JButton(this.checkAction);
    spellbutton.setHideActionText(true);
    spellbutton.setFocusable(false);
    toolbar.add(spellbutton);

    // TODO : i18n
    JToggleButton autospellbutton = new JToggleButton("AutoCheck", true);
    autospellbutton.setAction(autoCheckAction);
    autospellbutton.setHideActionText(true);
    autospellbutton.setFocusable(false);
    toolbar.add(autospellbutton);

    cons.insets = new Insets(5, 5, 5, 5);
    cons.fill = GridBagConstraints.BOTH;
    cons.weightx = 10.0f;
    cons.weighty = 10.0f;
    cons.gridx = 0;
    cons.gridy = 2;
    cons.weighty = 5.0f;
    final JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
            new JScrollPane(textArea), new JScrollPane(resultArea));
    splitPane.setDividerLocation(200);
    contentPane.add(splitPane, cons);

    cons.fill = GridBagConstraints.NONE;
    cons.gridx = 0;
    cons.gridy = 2;
    cons.weighty = 0.0f;
    cons.insets = new Insets(1, 10, 10, 1);
    cons.gridy = 3;
    contentPane.add(panel, cons);

    ltSupport = new LanguageToolSupport(this.frame, this.textArea);
    resultAreaHelper = new ResultArea(messages, ltSupport, resultArea);
    languageBox.selectLanguage(ltSupport.getLanguageTool().getLanguage());
    languageBox.setEnabled(!ltSupport.getConfig().getAutoDetect());
    autoDetectBox.setSelected(ltSupport.getConfig().getAutoDetect());

    languageBox.addItemListener(new ItemListener() {
      @Override
      public void itemStateChanged(ItemEvent e) {
        if(e.getStateChange() == ItemEvent.SELECTED)
        {
          // we cannot re-use the existing LT object anymore
          ltSupport.setLanguage((Language) languageBox.getSelectedItem());
        }
      }
    });
    autoDetectBox.addItemListener(new ItemListener() {
      @Override
      public void itemStateChanged(ItemEvent e) {
        boolean selected = (e.getStateChange() == ItemEvent.SELECTED);
        languageBox.setEnabled(!selected);
        ltSupport.getConfig().setAutoDetect(selected);
        if (selected) {
          Language detected = ltSupport.autoDetectLanguage(textArea.getText());
          languageBox.selectLanguage(detected);
        }
      }
    });    
    ltSupport.addLanguageToolListener(new LanguageToolListener() {
      @Override
      public void languageToolEventOccured(LanguageToolEvent event) {
        if (event.getType() == LanguageToolEvent.CHECKING_STARTED) {
          if(event.getCaller() == getFrame()) {
            startTime = System.currentTimeMillis();
            setWaitCursor();
            checkAction.setEnabled(false);
          }
        } else if (event.getType() == LanguageToolEvent.CHECKING_FINISHED) {
          if(event.getCaller() == getFrame()) {
            checkAction.setEnabled(true);
            unsetWaitCursor();
            resultAreaHelper.setRunTime(System.currentTimeMillis() - startTime);
            resultAreaHelper.displayResult();
          }
        }
        else if (event.getType() == LanguageToolEvent.LANGUAGE_CHANGED) {
          languageBox.selectLanguage(ltSupport.getLanguageTool().getLanguage());
        }
      }
    });
    textArea.setText(messages.getString("guiDemoText"));
    frame.pack();
    frame.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
    frame.setLocationByPlatform(true);
    maybeStartServer();
  }

  private String getLabel(String key) {
    return StringTools.getLabel(messages.getString(key));
  }

  private int getMnemonic(String key) {
    return StringTools.getMnemonic(messages.getString(key));
  }
  
  private KeyStroke getMenuKeyStroke(int keyEvent) {
    return KeyStroke.getKeyStroke(keyEvent, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask());
  }
  
  private JMenuBar createMenuBar() {
    JMenuBar menuBar = new JMenuBar();
    final JMenu fileMenu = new JMenu(getLabel("guiMenuFile"));
    fileMenu.setMnemonic(getMnemonic("guiMenuFile"));
    final JMenu editMenu = new JMenu(getLabel("guiMenuEdit"));
    editMenu.setMnemonic(getMnemonic("guiMenuEdit"));
    final JMenu grammarMenu = new JMenu(getLabel("guiMenuGrammar"));
    grammarMenu.setMnemonic(getMnemonic("guiMenuGrammar"));
    final JMenu helpMenu = new JMenu(getLabel("guiMenuHelp"));
    helpMenu.setMnemonic(getMnemonic("guiMenuHelp"));
    
    fileMenu.add(openAction);
    fileMenu.add(saveAction);
    fileMenu.add(saveAsAction);
    fileMenu.addSeparator();
    fileMenu.add(new HideAction());
    fileMenu.addSeparator();
    fileMenu.add(new QuitAction());
    
    grammarMenu.add(checkAction);
    JCheckBoxMenuItem item = new JCheckBoxMenuItem();
    item.setSelected(true);
    item.setAction(autoCheckAction);
    grammarMenu.add(item);
    grammarMenu.add(new CheckClipboardAction());
    grammarMenu.add(new TagTextAction());
    grammarMenu.add(new AddRulesAction());
    grammarMenu.add(new OptionsAction());
    
    helpMenu.add(new AboutAction());

    undoRedo.undoAction.putValue(Action.NAME, getLabel("guiMenuUndo"));
    undoRedo.undoAction.putValue(Action.MNEMONIC_KEY, getMnemonic("guiMenuUndo"));
    undoRedo.redoAction.putValue(Action.NAME, getLabel("guiMenuRedo"));
    undoRedo.redoAction.putValue(Action.MNEMONIC_KEY, getMnemonic("guiMenuRedo"));
            
    editMenu.add(undoRedo.undoAction);
    editMenu.add(undoRedo.redoAction);
    editMenu.addSeparator();
    
    Action a;
    Image img;

    a = this.textArea.getActionMap().get(DefaultEditorKit.cutAction);
    img = Toolkit.getDefaultToolkit().getImage(
            JLanguageTool.getDataBroker().getFromResourceDirAsUrl("sc_cut.png"));
    a.putValue(Action.SMALL_ICON, new ImageIcon(img));
    img = Toolkit.getDefaultToolkit().getImage(
            JLanguageTool.getDataBroker().getFromResourceDirAsUrl("lc_cut.png"));
    a.putValue(Action.LARGE_ICON_KEY, new ImageIcon(img));
    a.putValue(Action.NAME, getLabel("guiMenuCut"));
    a.putValue(Action.MNEMONIC_KEY, KeyEvent.VK_T);
    editMenu.add(a);

    a = textArea.getActionMap().get(DefaultEditorKit.copyAction);
    img = Toolkit.getDefaultToolkit().getImage(
            JLanguageTool.getDataBroker().getFromResourceDirAsUrl("sc_copy.png"));
    a.putValue(Action.SMALL_ICON, new ImageIcon(img));
    img = Toolkit.getDefaultToolkit().getImage(
            JLanguageTool.getDataBroker().getFromResourceDirAsUrl("lc_copy.png"));
    a.putValue(Action.LARGE_ICON_KEY, new ImageIcon(img));
    a.putValue(Action.NAME, getLabel("guiMenuCopy"));
    a.putValue(Action.MNEMONIC_KEY, KeyEvent.VK_C);
    editMenu.add(a);

    a = textArea.getActionMap().get(DefaultEditorKit.pasteAction);
    img = Toolkit.getDefaultToolkit().getImage(
            JLanguageTool.getDataBroker().getFromResourceDirAsUrl("sc_paste.png"));
    a.putValue(Action.SMALL_ICON, new ImageIcon(img));
    img = Toolkit.getDefaultToolkit().getImage(
            JLanguageTool.getDataBroker().getFromResourceDirAsUrl("lc_paste.png"));
    a.putValue(Action.LARGE_ICON_KEY, new ImageIcon(img));
    a.putValue(Action.NAME, getLabel("guiMenuPaste"));
    a.putValue(Action.MNEMONIC_KEY, KeyEvent.VK_P);
    editMenu.add(a);

    editMenu.addSeparator();

    a = textArea.getActionMap().get(DefaultEditorKit.selectAllAction);
    a.putValue(Action.NAME, getLabel("guiMenuSelectAll"));
    a.putValue(Action.MNEMONIC_KEY, KeyEvent.VK_A);
    editMenu.add(a);

    menuBar.add(fileMenu);
    menuBar.add(editMenu);
    menuBar.add(grammarMenu);
    menuBar.add(helpMenu);
    return menuBar;
  }
  
  private void setLookAndFeel() {
    try {
      for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
        if ("Nimbus".equals(info.getName())) {
          UIManager.setLookAndFeel(info.getClassName());
          break;
        }
      }
    } catch (Exception ignored) {
      // Well, what can we do...
    }
  }

  private PopupMenu makePopupMenu() {
    final PopupMenu popup = new PopupMenu();
    final ActionListener rmbListener = new TrayActionRMBListener();
    // Enable or disable embedded HTTP server:
    enableHttpServerItem = new CheckboxMenuItem(StringTools.getLabel(messages.getString("tray_menu_enable_server")));
    enableHttpServerItem.setState(httpServer != null && httpServer.isRunning());
    enableHttpServerItem.addItemListener(new TrayActionItemListener());
    popup.add(enableHttpServerItem);
    // Check clipboard text:
    final MenuItem checkClipboardItem =
            new MenuItem(StringTools.getLabel(messages.getString("guiMenuCheckClipboard")));
    checkClipboardItem.addActionListener(rmbListener);
    popup.add(checkClipboardItem);
    // Open main window:
    final MenuItem restoreItem = new MenuItem(StringTools.getLabel(messages.getString("guiMenuShowMainWindow")));
    restoreItem.addActionListener(rmbListener);
    popup.add(restoreItem);
    // Exit:
    final MenuItem exitItem = new MenuItem(StringTools.getLabel(messages.getString("guiMenuQuit")));
    exitItem.addActionListener(rmbListener);
    popup.add(exitItem);
    return popup;
  }

  void checkClipboardText() {
    final String s = getClipboardText();
    textArea.setText(s);
  }

  void hideToTray() {
    if (!isInTray) {
      final SystemTray tray = SystemTray.getSystemTray();
      final String iconPath = tray.getTrayIconSize().height > 16 ? TRAY_ICON : TRAY_SMALL_ICON;
      final URL iconUrl = JLanguageTool.getDataBroker().getFromResourceDirAsUrl(iconPath);
      final Image img = Toolkit.getDefaultToolkit().getImage(iconUrl);
      final PopupMenu popup = makePopupMenu();
      try {
        trayIcon = new TrayIcon(img, TRAY_TOOLTIP, popup);
        trayIcon.addMouseListener(new TrayActionListener());
        setTrayIcon();
        tray.add(trayIcon);
      } catch (AWTException e1) {
        Tools.showError(e1);
      }
    }
    isInTray = true;
    frame.setVisible(false);
  }

  void tagText() {
    if (StringTools.isEmpty(textArea.getText().trim())) {
      textArea.setText(messages.getString("enterText2"));
      return;
    }
    setWaitCursor();
    new Thread() {
      @Override
      public void run() {
        try {
          tagTextAndDisplayResults();
        } finally {
          SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
              unsetWaitCursor();
            }
          });
        }
      }
    }.start();
  }

  void quitOrHide() {
    if (closeHidesToTray) {
      hideToTray();
    } else {
      quit();
    }
  }

  void quit() {
    stopServer();
    try {
      Configuration config = ltSupport.getConfig();
      config.setLanguage(ltSupport.getLanguageTool().getLanguage());
      config.saveConfiguration(ltSupport.getLanguageTool().getLanguage());
    } catch (IOException e) {
      Tools.showError(e);
    }
    frame.setVisible(false);
    JLanguageTool.removeTemporaryFiles();
    System.exit(0);
  }

  private void setTrayIcon() {
    if (trayIcon != null) {
      final SystemTray tray = SystemTray.getSystemTray();
      final boolean httpServerRunning = httpServer != null && httpServer.isRunning();
      final boolean smallTray = tray.getTrayIconSize().height <= 16;
      final String iconPath;
      if (httpServerRunning) {
        trayIcon.setToolTip(messages.getString("tray_tooltip_server_running"));
        iconPath = smallTray ? TRAY_SMALL_SERVER_ICON : TRAY_SERVER_ICON;
      } else {
        trayIcon.setToolTip(TRAY_TOOLTIP);
        iconPath = smallTray ? TRAY_SMALL_ICON : TRAY_ICON;
      }
      final URL iconUrl = JLanguageTool.getDataBroker().getFromResourceDirAsUrl(iconPath);
      final Image img = Toolkit.getDefaultToolkit().getImage(iconUrl);
      trayIcon.setImage(img);
    }
  }

  private void showGUI() {
    frame.setVisible(true);
  }

  private void restoreFromTray() {
    frame.setVisible(true);
  }

  // show GUI and check the text from clipboard/selection:
  private void restoreFromTrayAndCheck() {
    final String s = getClipboardText();
    restoreFromTray();
    textArea.setText(s);
  }

  private String getClipboardText() {
    // get text from clipboard or selection:
    Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemSelection();
    if (clipboard == null) { // on Windows
      clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
    }
    String s;
    final Transferable data = clipboard.getContents(this);
    try {
      if (data != null
          && data.isDataFlavorSupported(DataFlavor.getTextPlainUnicodeFlavor())) {
        final DataFlavor df = DataFlavor.getTextPlainUnicodeFlavor();
        final Reader sr = df.getReaderForText(data);
        s = StringTools.readerToString(sr);
      } else {
        s = "";
      }
    } catch (Exception ex) {
      if (data != null) {
        s = data.toString();
      } else {
        s = "";
      }
    }
    return s;
  }

  private boolean maybeStartServer() {
    Configuration config = ltSupport.getConfig();
    if (config.getRunServer()) {
      try {
        final HTTPServerConfig serverConfig = new HTTPServerConfig(config.getServerPort(), false);
        httpServer = new HTTPServer(serverConfig, true);
        httpServer.run();
        if (enableHttpServerItem != null) {
          enableHttpServerItem.setState(httpServer.isRunning());
          setTrayIcon();
        }
      } catch (PortBindingException e) {
        JOptionPane.showMessageDialog(null, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
      }
    }
    return httpServer != null && httpServer.isRunning();
  }

  private void stopServer() {
    if (httpServer != null) {
      httpServer.stop();
      if (enableHttpServerItem != null) {
        enableHttpServerItem.setState(httpServer.isRunning());
        setTrayIcon();
      }
      httpServer = null;
    }
  }

  private void checkTextAndDisplayResults() {
    if (StringTools.isEmpty(textArea.getText().trim())) {
      textArea.setText(messages.getString("enterText2"));
      return;
    }
    ltSupport.checkImmediately(getFrame());
  }

  private String getStackTraceAsHtml(Exception e) {
    return "<br><br><b><font color=\"red\">"
         + org.languagetool.tools.Tools.getFullStackTrace(e).replace("\n", "<br/>")
         + "</font></b><br>";
  }

  private void setWaitCursor() {
    frame.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    // For some reason we also have to set the cursor here so it also shows
    // when user starts checking text with Ctrl+Return:
    textArea.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    resultArea.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
  }

  private void unsetWaitCursor() {
    frame.setCursor(Cursor.getDefaultCursor());
    textArea.setCursor(Cursor.getDefaultCursor());
    resultArea.setCursor(Cursor.getDefaultCursor());
  }

  private void tagTextAndDisplayResults() {
    final JLanguageTool langTool = ltSupport.getLanguageTool();
    // tag text
    final List<String> sentences = langTool.sentenceTokenize(textArea.getText());
    final StringBuilder sb = new StringBuilder();
    try {
      for (String sent : sentences) {
        final AnalyzedSentence analyzedText = langTool.getAnalyzedSentence(sent);
        final String analyzedTextString = StringTools.escapeHTML(analyzedText.toString(", ")).
                replace("[", "<font color='#888888'>[").replace("]", "]</font>");
        sb.append(analyzedTextString).append("\n");
      }
    } catch (Exception e) {
      sb.append(getStackTraceAsHtml(e));
    }
    // This method is thread safe
    resultArea.setText(HTML_FONT_START + sb.toString() + HTML_FONT_END);
  }

  private void setTrayMode(boolean trayMode) {
    this.closeHidesToTray = trayMode;
  }
  
  public static void main(final String[] args) {
    JnaTools.setBugWorkaroundProperty();
    try {
      final Main prg = new Main();
      if (args.length == 1 && (args[0].equals("-t") || args[0].equals("--tray"))) {
        // dock to systray on startup
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
          @Override
          public void run() {
            try {
              prg.createGUI();
              prg.setTrayMode(true);
              prg.hideToTray();
            } catch (Exception e) {
              Tools.showError(e);
              System.exit(1);
            }
          }
        });
      } else if (args.length >= 1) {
        System.out.println("Usage: java org.languagetool.gui.Main [-t|--tray]");
        System.out.println("  -t, --tray: dock LanguageTool to system tray on startup");
      } else {
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
          @Override
          public void run() {
            try {
              prg.createGUI();
              prg.showGUI();
            } catch (Exception e) {
              Tools.showError(e);
            }
          }
        });
      }
    } catch (Exception e) {
      Tools.showError(e);
    }
  }

  private class ControlReturnTextCheckingListener implements KeyListener {

    @Override
    public void keyTyped(KeyEvent e) {}
    @Override
    public void keyReleased(KeyEvent e) {}

    @Override
    public void keyPressed(KeyEvent e) {
      if (e.getKeyCode() == KeyEvent.VK_ENTER) {
        if ((e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) == KeyEvent.CTRL_DOWN_MASK) {
          checkTextAndDisplayResults();
        }
      }
    }

  }
  
  //
  // The System Tray stuff
  //

  class TrayActionItemListener implements ItemListener {
    @Override
    public void itemStateChanged(ItemEvent e) {
      try {
        final ConfigurationDialog configDialog = ltSupport.getCurrentConfigDialog();
        final Configuration config = ltSupport.getConfig();
        if (e.getStateChange() == ItemEvent.SELECTED) {
          config.setRunServer(true);
          final boolean serverStarted = maybeStartServer();
          enableHttpServerItem.setState(serverStarted);
          config.setRunServer(serverStarted);
          config.saveConfiguration(ltSupport.getLanguageTool().getLanguage());
          if (configDialog != null) {
            configDialog.setRunServer(true);
          }
        } else if (e.getStateChange() == ItemEvent.DESELECTED) {
          config.setRunServer(false);
          config.saveConfiguration(ltSupport.getLanguageTool().getLanguage());
          if (configDialog != null) {
            configDialog.setRunServer(false);
          }
          stopServer();
        }
      } catch (IOException ex) {
        Tools.showError(ex);
      }
    }
  }

  class TrayActionRMBListener implements ActionListener {

    @Override
    public void actionPerformed(ActionEvent e) {
      if (isCommand(e, "guiMenuCheckClipboard")) {
        restoreFromTrayAndCheck();
      } else if (isCommand(e, "guiMenuShowMainWindow")) {
        restoreFromTray();
      } else if (isCommand(e, "guiMenuQuit")) {
        quit();
      } else {
        JOptionPane.showMessageDialog(null, "Unknown action: "
            + e.getActionCommand(), "Error", JOptionPane.ERROR_MESSAGE);
      }
    }

    private boolean isCommand(ActionEvent e, String label) {
      return e.getActionCommand().equalsIgnoreCase(StringTools.getLabel(messages.getString(label)));
    }

  }

  class TrayActionListener implements MouseListener {

    @Override
    public void mouseClicked(@SuppressWarnings("unused")MouseEvent e) {
      if (frame.isVisible() && frame.isActive()) {
        frame.setVisible(false);
      } else if (frame.isVisible() && !frame.isActive()) {
        frame.toFront();
        restoreFromTrayAndCheck();
      } else {        
        restoreFromTrayAndCheck();
      }
    }

    @Override
    public void mouseEntered(@SuppressWarnings("unused") MouseEvent e) {}
    @Override
    public void mouseExited(@SuppressWarnings("unused")MouseEvent e) {}
    @Override
    public void mousePressed(@SuppressWarnings("unused")MouseEvent e) {}
    @Override
    public void mouseReleased(@SuppressWarnings("unused")MouseEvent e) {}

  }

  class CloseListener implements WindowListener {

    @Override
    public void windowClosing(@SuppressWarnings("unused")WindowEvent e) {
      quitOrHide();
    }

    @Override
    public void windowActivated(@SuppressWarnings("unused")WindowEvent e) {}
    @Override
    public void windowClosed(@SuppressWarnings("unused")WindowEvent e) {}
    @Override
    public void windowDeactivated(@SuppressWarnings("unused")WindowEvent e) {}
    @Override
    public void windowDeiconified(@SuppressWarnings("unused")WindowEvent e) {}
    @Override
    public void windowIconified(@SuppressWarnings("unused")WindowEvent e) {}
    @Override
    public void windowOpened(@SuppressWarnings("unused")WindowEvent e) {}

  }

  static class PlainTextFileFilter extends FileFilter {

    @Override
    public boolean accept(final File f) {
        final boolean isTextFile = f.getName().toLowerCase().endsWith(".txt");
        return isTextFile || f.isDirectory();
    }

    @Override
    public String getDescription() {
      return "*.txt";
    }

  }
  
  class OpenAction extends AbstractAction {

    public OpenAction() {
      super(getLabel("guiMenuOpen"));
      putValue(Action.SHORT_DESCRIPTION, messages.getString("guiMenuOpenShortDesc"));
      putValue(Action.LONG_DESCRIPTION, messages.getString("guiMenuOpenLongDesc"));
      putValue(Action.MNEMONIC_KEY, getMnemonic("guiMenuOpen"));
      putValue(Action.ACCELERATOR_KEY, getMenuKeyStroke(KeyEvent.VK_O));
      Image img;
      img = Toolkit.getDefaultToolkit().getImage(
              JLanguageTool.getDataBroker().getFromResourceDirAsUrl("sc_open.png"));
      putValue(Action.SMALL_ICON, new ImageIcon(img));
      img = Toolkit.getDefaultToolkit().getImage(
              JLanguageTool.getDataBroker().getFromResourceDirAsUrl("lc_open.png"));
      putValue(Action.LARGE_ICON_KEY, new ImageIcon(img));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      loadFile();
    }
  }

  class SaveAction extends AbstractAction {

    public SaveAction() {
      super(getLabel("guiMenuSave"));
      putValue(Action.SHORT_DESCRIPTION, messages.getString("guiMenuSaveShortDesc"));
      putValue(Action.LONG_DESCRIPTION, messages.getString("guiMenuSaveLongDesc"));
      putValue(Action.MNEMONIC_KEY, getMnemonic("guiMenuSave"));
      putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_S, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
      Image img;
      img = Toolkit.getDefaultToolkit().getImage(
              JLanguageTool.getDataBroker().getFromResourceDirAsUrl("sc_save.png"));
      putValue(Action.SMALL_ICON, new ImageIcon(img));
      img = Toolkit.getDefaultToolkit().getImage(
              JLanguageTool.getDataBroker().getFromResourceDirAsUrl("lc_save.png"));
      putValue(Action.LARGE_ICON_KEY, new ImageIcon(img));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      saveFile(false);
    }
  }

  class SaveAsAction extends AbstractAction {

    public SaveAsAction() {
      super(getLabel("guiMenuSaveAs"));
      putValue(Action.SHORT_DESCRIPTION, messages.getString("guiMenuSaveAsShortDesc"));
      putValue(Action.LONG_DESCRIPTION, messages.getString("guiMenuSaveAsLongDesc"));
      putValue(Action.MNEMONIC_KEY, getMnemonic("guiMenuSaveAs"));
      Image img;
      img = Toolkit.getDefaultToolkit().getImage(
              JLanguageTool.getDataBroker().getFromResourceDirAsUrl("sc_saveas.png"));
      putValue(Action.SMALL_ICON, new ImageIcon(img));
      img = Toolkit.getDefaultToolkit().getImage(
              JLanguageTool.getDataBroker().getFromResourceDirAsUrl("lc_saveas.png"));
      putValue(Action.LARGE_ICON_KEY, new ImageIcon(img));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      saveFile(true);
    }
  }
  class CheckClipboardAction extends AbstractAction {

    public CheckClipboardAction() {
      super(getLabel("guiMenuCheckClipboard"));
      putValue(Action.MNEMONIC_KEY, getMnemonic("guiMenuCheckClipboard"));
      putValue(Action.ACCELERATOR_KEY, getMenuKeyStroke(KeyEvent.VK_Y));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      checkClipboardText();
    }
  }

  class TagTextAction extends AbstractAction {

    public TagTextAction() {
      super(getLabel("guiTagText"));
      putValue(Action.MNEMONIC_KEY, getMnemonic("guiTagText"));
      putValue(Action.ACCELERATOR_KEY, getMenuKeyStroke(KeyEvent.VK_T));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      tagText();
    }
  }

  class AddRulesAction extends AbstractAction {

    public AddRulesAction() {
      super(getLabel("guiMenuAddRules"));
      putValue(Action.MNEMONIC_KEY, getMnemonic("guiMenuAddRules"));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      addLanguage();
    }
  }

  class OptionsAction extends AbstractAction {

    public OptionsAction() {
      super(getLabel("guiMenuOptions"));
      putValue(Action.MNEMONIC_KEY, getMnemonic("guiMenuOptions"));
      putValue(Action.ACCELERATOR_KEY, getMenuKeyStroke(KeyEvent.VK_S));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      showOptions();
    }
  }

  class HideAction extends AbstractAction {

    public HideAction() {
      super(getLabel("guiMenuHide"));
      putValue(Action.MNEMONIC_KEY, getMnemonic("guiMenuHide"));
      putValue(Action.ACCELERATOR_KEY, getMenuKeyStroke(KeyEvent.VK_D));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      hideToTray();
    }
  }

  class QuitAction extends AbstractAction {

    public QuitAction() {
      super(getLabel("guiMenuQuit"));
      putValue(Action.MNEMONIC_KEY, getMnemonic("guiMenuQuit"));
      putValue(Action.ACCELERATOR_KEY, getMenuKeyStroke(KeyEvent.VK_Q));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      quit();
    }
  }

  class AboutAction extends AbstractAction {

    public AboutAction() {
      super(getLabel("guiMenuAbout"));
      putValue(Action.MNEMONIC_KEY, getMnemonic("guiMenuAbout"));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      AboutDialog about = new AboutDialog(messages, getFrame());
      about.show();
    }
  }
 
  class CheckAction extends AbstractAction {

    public CheckAction() {
      super(getLabel("checkText"));
      putValue(Action.SHORT_DESCRIPTION, messages.getString("checkTextShortDesc"));
      putValue(Action.LONG_DESCRIPTION, messages.getString("checkTextLongDesc"));
      putValue(Action.MNEMONIC_KEY, getMnemonic("checkText"));
      Image img;
      img = Toolkit.getDefaultToolkit().getImage(
              JLanguageTool.getDataBroker().getFromResourceDirAsUrl("sc_spelldialog.png"));
      putValue(Action.SMALL_ICON, new ImageIcon(img));
      img = Toolkit.getDefaultToolkit().getImage(
              JLanguageTool.getDataBroker().getFromResourceDirAsUrl("lc_spelldialog.png"));
      putValue(Action.LARGE_ICON_KEY, new ImageIcon(img));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      checkTextAndDisplayResults();
    }
  }
  
  class AutoCheckAction extends AbstractAction {

    private boolean enable;

    public AutoCheckAction(boolean initial) {
      super(getLabel("autoCheckText"));
      putValue(Action.SHORT_DESCRIPTION, messages.getString("autoCheckTextShortDesc"));
      putValue(Action.LONG_DESCRIPTION, messages.getString("autoCheckTextLongDesc"));
      putValue(Action.MNEMONIC_KEY, getMnemonic("autoCheckText"));
      this.enable = initial;
      Image img;
      img = Toolkit.getDefaultToolkit().getImage(
              JLanguageTool.getDataBroker().getFromResourceDirAsUrl("sc_spellonline.png"));
      putValue(Action.SMALL_ICON, new ImageIcon(img));
      img = Toolkit.getDefaultToolkit().getImage(
              JLanguageTool.getDataBroker().getFromResourceDirAsUrl("lc_spellonline.png"));
      putValue(Action.LARGE_ICON_KEY, new ImageIcon(img));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      enable = !enable;
      ltSupport.setBackgroundCheckEnabled(enable);
    }
  }
}
