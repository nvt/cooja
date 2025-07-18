/*
 * Copyright (c) 2012, Swedish Institute of Computer Science.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 */

package org.contikios.cooja.plugins;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.PatternSyntaxException;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import org.contikios.cooja.ClassDescription;
import org.contikios.cooja.Cooja;
import org.contikios.cooja.Mote;
import org.contikios.cooja.PluginType;
import org.contikios.cooja.Simulation;
import org.contikios.cooja.TimeEvent;
import org.contikios.cooja.VisPlugin;
import org.contikios.cooja.dialogs.TableColumnAdjuster;
import org.contikios.cooja.dialogs.UpdateAggregator;
import org.contikios.cooja.mote.memory.MemoryBuffer;
import org.contikios.cooja.mote.memory.MemoryInterface;
import org.contikios.cooja.mote.memory.MemoryInterface.SegmentMonitor;
import org.contikios.cooja.mote.memory.VarMemory;
import org.contikios.cooja.motes.AbstractEmulatedMote;
import org.contikios.cooja.util.ArrayQueue;
import org.contikios.cooja.util.EventTriggers;
import org.contikios.cooja.util.IPUtils;
import org.contikios.cooja.util.StringUtils;
import org.jdom2.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Fredrik Osterlind, Niclas Finne
 */
@ClassDescription("Buffer view")
@PluginType(PluginType.PType.SIM_PLUGIN)
public class BufferListener extends VisPlugin {
  private static final Logger logger = LoggerFactory.getLogger(BufferListener.class);

  private final static int COLUMN_TIME = 0;
  private final static int COLUMN_FROM = 1;
  private final static int COLUMN_TYPE = 2;
  private final static int COLUMN_DATA = 3;
  private final static int COLUMN_SOURCE = 4;
  private final static String[] COLUMN_NAMES = {
    "Time ms",
    "Mote",
    "Access",
    "*******",
    "Source",
  };

  private static final long TIME_SECOND = 1000*Simulation.MILLISECOND;
  private static final long TIME_MINUTE = 60*TIME_SECOND;
  private static final long TIME_HOUR = 60*TIME_MINUTE;

  final static int MAX_BUFFER_SIZE = 16 * 1024;

  private static final ArrayList<Class<? extends Parser>> bufferParsers =
          new ArrayList<>();
  static {
    registerBufferParser(ByteArrayParser.class);
    registerBufferParser(IntegerParser.class);
    registerBufferParser(TerminatedStringParser.class);
    registerBufferParser(PrintableCharactersParser.class);
    registerBufferParser(IPv6AddressParser.class);
    /* TODO Add parsers: ValueToWidth, AccessHeatmap, .. */
    registerBufferParser(GraphicalHeight4BitsParser.class);
    registerBufferParser(GraphicalGrayscale4BitsParser.class);
  }

  /* TODO Hide identical lines? */

  private static final ArrayList<Class<? extends Buffer>> bufferTypes =
          new ArrayList<>();
  static {
    registerBufferType(PacketbufBuffer.class);
    registerBufferType(PacketbufPointerBuffer.class);
    registerBufferType(NodeIDBuffer.class);
    registerBufferType(Queuebuf0Buffer.class);
    /* TODO Add buffers: Queuebuf(1,2,3,4,..). */
    registerBufferType(CustomVariableBuffer.class);
    registerBufferType(CustomIntegerBuffer.class);
    registerBufferType(CustomPointerBuffer.class);
  }

  private Parser parser;
  private Buffer buffer;
  @Override
  public void startPlugin() {
    super.startPlugin();
    if (parser == null) {
      setParser(ByteArrayParser.class);
    }
    if (buffer == null) {
      Buffer b = createBufferInstance(PacketbufBuffer.class);
      if (b != null) {
        if (b.configure(BufferListener.this)) {
          setBuffer(b);
        }
      }
    }
  }

  private boolean formatTimeString;
  private boolean hasHours;

  private final JTable logTable;
  private final TableRowSorter<TableModel> logFilter;
  private final ArrayQueue<BufferAccess> logs = new ArrayQueue<>();

  private final Simulation simulation;

  private final JTextField filterTextField;
  private final JLabel filterLabel = new JLabel("Filter: ");
  private final Color filterTextFieldBackground;

  private final AbstractTableModel model;

  private boolean backgroundColors;
  private final JCheckBoxMenuItem colorCheckbox;

  private boolean inverseFilter;
  private final JCheckBoxMenuItem inverseFilterCheckbox;

  private boolean hideReads = true;
  private final JCheckBoxMenuItem hideReadsCheckbox;

  private boolean withStackTrace;
  private final JCheckBoxMenuItem withStackTraceCheckbox;

  private final JMenu bufferMenu = new JMenu("Buffer");
  private final JMenu parserMenu = new JMenu("Show as");

  private final ArrayList<Mote> motes = new ArrayList<>();
  private final ArrayList<SegmentMemoryMonitor> memoryMonitors = new ArrayList<>();

  private TimeEvent hourTimeEvent = new TimeEvent() {
    @Override
    public void execute(long t) {
      hasHours = true;
      repaintTimeColumn();
      hourTimeEvent = null;
    }
  };

  private static final int UPDATE_INTERVAL = 250;
  private final UpdateAggregator<BufferAccess> logUpdateAggregator = new UpdateAggregator<>(UPDATE_INTERVAL) {
    private final Runnable scroll = new Runnable() {
      @Override
      public void run() {
        logTable.scrollRectToVisible(
            new Rectangle(0, logTable.getHeight() - 2, 1, logTable.getHeight()));
      }
    };
    @Override
    protected void handle(List<BufferAccess> ls) {
      boolean isVisible = true;
      if (logTable.getRowCount() > 0) {
        Rectangle visible = logTable.getVisibleRect();
        if (visible.y + visible.height < logTable.getHeight()) {
          isVisible = false;
        }
      }

      /* Add */
      int index = logs.size();
      logs.addAll(ls);
      model.fireTableRowsInserted(index, logs.size()-1);

      /* Remove old */
      int removed = 0;
      while (logs.size() > simulation.getEventCentral().getLogOutputBufferSize()) {
        logs.remove(0);
        removed++;
      }
      if (removed > 0) {
        model.fireTableRowsDeleted(0, removed-1);
      }

      if (isVisible) {
        SwingUtilities.invokeLater(scroll);
      }
    }
  };

  /**
   * @param simulation Simulation
   * @param gui GUI
   */
  public BufferListener(final Simulation simulation, final Cooja gui) {
    super("Buffer Listener - " + "?" + " motes", gui);
    this.simulation = simulation;

    if (simulation.getSimulationTime() > TIME_HOUR) {
      hasHours = true;
      hourTimeEvent = null;
    } else {
      simulation.scheduleEvent(hourTimeEvent, TIME_HOUR);
    }

    model = new AbstractTableModel() {
      @Override
      public String getColumnName(int col) {
        if (col == COLUMN_TIME && formatTimeString) {
          return "Time";
        }
        return COLUMN_NAMES[col];
      }
      @Override
      public int getRowCount() {
        return logs.size();
      }
      @Override
      public int getColumnCount() {
        return COLUMN_NAMES.length;
      }
      @Override
      public Object getValueAt(int row, int col) {
        BufferAccess log = logs.get(row);
        if (col == COLUMN_TIME) {
          return log.getTime(formatTimeString, hasHours);
        } else if (col == COLUMN_FROM) {
          return log.getID();
        } else if (col == COLUMN_TYPE) {
          return log.getType();
        } else if (col == COLUMN_DATA) {
          return parser.parse(log);
        } else if (col == COLUMN_SOURCE) {
          return log.getSource();
        }
        return null;
      }
    };

    logTable = new JTable(model) {
      @Override
      public String getToolTipText(MouseEvent e) {
        java.awt.Point p = e.getPoint();
        int rowIndex = rowAtPoint(p);
        int colIndex = columnAtPoint(p);
        if (rowIndex < 0 || colIndex < 0) {
          return super.getToolTipText(e);
        }
        int row = convertRowIndexToModel(rowIndex);
        int column = convertColumnIndexToModel(colIndex);
        if (row < 0 || column < 0) {
          return super.getToolTipText(e);
        }

        if (column == COLUMN_SOURCE) {
          BufferAccess ba = logs.get(row);
          if (ba.stackTrace != null) {
            return
            "<html><pre>" +
            ba.stackTrace +
            "</pre></html>";
          }
          return "No stack trace (enable in popup menu)";
        }
        if (column == COLUMN_DATA) {
          BufferAccess ba = logs.get(row);
          return
          "<html><pre>" +
          "Address: " + (ba.address<=0?"null":String.format("%016x\n", ba.address)) +
          StringUtils.hexDump(ba.mem, 4, 4) +
          "</pre></html>";
        }

        return super.getToolTipText(e);
      }
    };
    DefaultTableCellRenderer cellRenderer = new DefaultTableCellRenderer() {
      private final Color[] BG_COLORS = {
          new Color(200, 200, 200),
          new Color(200, 200, 255),
          new Color(200, 255, 200),
          new Color(200, 255, 255),
          new Color(255, 200, 200),
          new Color(255, 255, 200),
          new Color(255, 255, 255),
          new Color(255, 220, 200),
          new Color(220, 255, 220),
          new Color(255, 200, 255),
      };
      @Override
      public Component getTableCellRendererComponent(JTable table,
          Object value, boolean isSelected, boolean hasFocus, int row,
          int column) {
        if (row >= logTable.getRowCount()) {
          if (value instanceof BufferAccess bufferAccess && parser instanceof GraphicalParser graphicalParser) {
            graphicalParserPanel.update(bufferAccess, graphicalParser);
            return graphicalParserPanel;
          }
          return super.getTableCellRendererComponent(
              table, value, isSelected, hasFocus, row, column);
        }

        Color bgColor = null;
        if (backgroundColors) {
          BufferAccess d = logs.get(logTable.getRowSorter().convertRowIndexToModel(row));
          char last = d.getID().charAt(d.getID().length()-1);
          if (last >= '0' && last <= '9') {
            bgColor = BG_COLORS[last - '0'];
          }
        }
        if (isSelected) {
          bgColor = table.getSelectionBackground();
        }

        if (value instanceof BufferAccess bufferAccess && parser instanceof GraphicalParser graphicalParser) {
          graphicalParserPanel.update(bufferAccess, graphicalParser);
          graphicalParserPanel.setBackground(bgColor);
          return graphicalParserPanel;
        } else {
          setBackground(bgColor);
        }

        return super.getTableCellRendererComponent(
            table, value, isSelected, hasFocus, row, column);
      }
    };
    logTable.getColumnModel().getColumn(COLUMN_TIME).setCellRenderer(cellRenderer);
    logTable.getColumnModel().getColumn(COLUMN_FROM).setCellRenderer(cellRenderer);
    logTable.getColumnModel().getColumn(COLUMN_TYPE).setCellRenderer(cellRenderer);
    logTable.getColumnModel().getColumn(COLUMN_SOURCE).setCellRenderer(cellRenderer);
    logTable.getColumnModel().getColumn(COLUMN_DATA).setCellRenderer(cellRenderer);
    logTable.setFillsViewportHeight(true);
    logTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
    logTable.setFont(new Font("Monospaced", Font.PLAIN, 12));
    logTable.addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_SPACE) {
          showInAllAction.actionPerformed(null);
        }
      }
    });
    logFilter = new TableRowSorter<>(model);
    for (int i = 0, n = model.getColumnCount(); i < n; i++) {
      logFilter.setSortable(i, false);
    }
    logTable.setRowSorter(logFilter);

    /* Toggle time format */
    logTable.getTableHeader().addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        int colIndex = logTable.columnAtPoint(e.getPoint());
        int columnIndex = logTable.convertColumnIndexToModel(colIndex);

        if (columnIndex != COLUMN_TIME) {
          return;
        }
        formatTimeString = !formatTimeString;
        repaintTimeColumn();
      }
    });
    logTable.addMouseListener(new MouseAdapter() {
      private Parser lastParser;
      @Override
      public void mousePressed(MouseEvent e) {
        if (e.getButton() != MouseEvent.BUTTON2) {
          return;
        }
        int colIndex = logTable.columnAtPoint(e.getPoint());
        int columnIndex = logTable.convertColumnIndexToModel(colIndex);
        if (columnIndex == COLUMN_DATA) {
          /* Temporarily switch to byte parser */
          lastParser = parser;
          setParser(ByteArrayParser.class);
        }
      }
      @Override
      public void mouseExited(MouseEvent e) {
        if (lastParser != null) {
          /* Switch back to previous parser */
          setParser(lastParser.getClass());
          lastParser = null;
        }
      }
      @Override
      public void mouseReleased(MouseEvent e) {
        if (lastParser != null) {
          /* Switch back to previous parser */
          setParser(lastParser.getClass());
          lastParser = null;
        }
      }
      @Override
      public void mouseClicked(MouseEvent e) {
        int colIndex = logTable.columnAtPoint(e.getPoint());
        int columnIndex = logTable.convertColumnIndexToModel(colIndex);
        if (columnIndex != COLUMN_FROM) {
          return;
        }

        int rowIndex = logTable.rowAtPoint(e.getPoint());
        BufferAccess d = logs.get(logTable.getRowSorter().convertRowIndexToModel(rowIndex));
        if (d == null) {
          return;
        }
        gui.signalMoteHighlight(d.mote);
      }
    });

    /* Automatically update column widths */
    final TableColumnAdjuster adjuster = new TableColumnAdjuster(logTable, 0);
    adjuster.packColumns();
    logTable.getColumnModel().getColumn(COLUMN_DATA).setWidth(400);

    /* Popup menu */
    JPopupMenu popupMenu = new JPopupMenu();
    bufferMenu.addMenuListener(new MenuListener() {
      @Override
      public void menuSelected(MenuEvent e) {
        bufferMenu.removeAll();
        for (var btClass: bufferTypes) {
          if (btClass == CustomVariableBuffer.class) {
            bufferMenu.addSeparator();
          }
          JCheckBoxMenuItem mi = new JCheckBoxMenuItem(Cooja.getDescriptionOf(btClass), btClass == buffer.getClass());
          mi.addActionListener(bufferSelectedEvent -> {
                                 var b = createBufferInstance(btClass);
                                 if (b != null) {
                                   if (b.configure(BufferListener.this)) {
                                     setBuffer(b);
                                   }
                                 }
                               });
          bufferMenu.add(mi);
        }
      }
      @Override
      public void menuDeselected(MenuEvent e) {
      }
      @Override
      public void menuCanceled(MenuEvent e) {
      }
    });
    popupMenu.add(bufferMenu);
    parserMenu.addMenuListener(new MenuListener() {
      @Override
      public void menuSelected(MenuEvent e) {
        parserMenu.removeAll();
        for (var bpClass: bufferParsers) {
          JCheckBoxMenuItem mi = new JCheckBoxMenuItem(Cooja.getDescriptionOf(bpClass), bpClass == parser.getClass());
          mi.addActionListener(parserSelectedEvent -> setParser(bpClass));
          parserMenu.add(mi);
        }
      }
      @Override
      public void menuDeselected(MenuEvent e) {
      }
      @Override
      public void menuCanceled(MenuEvent e) {
      }
    });
    popupMenu.add(parserMenu);
    popupMenu.addSeparator();
    JMenu copyClipboard = new JMenu("Copy to clipboard");
    copyClipboard.add(new JMenuItem(new AbstractAction("All") {
      @Override
      public void actionPerformed(ActionEvent e) {
        var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < logTable.getRowCount(); i++) {
          sb.append(logTable.getValueAt(i, COLUMN_TIME)).append("\t");
          sb.append(logTable.getValueAt(i, COLUMN_FROM)).append("\t");
          sb.append(logTable.getValueAt(i, COLUMN_TYPE)).append("\t");
          if (parser instanceof GraphicalParser) {
            BufferAccess ba = (BufferAccess) logTable.getValueAt(i, COLUMN_DATA);
            sb.append(ba.getAsHex());
          } else {
            sb.append(logTable.getValueAt(i, COLUMN_DATA));
          }
          sb.append("\t");
          sb.append(logTable.getValueAt(i, COLUMN_SOURCE)).append("\n");
        }
        StringSelection stringSelection = new StringSelection(sb.toString());
        clipboard.setContents(stringSelection, null);
      }
    }));
    copyClipboard.add(new JMenuItem(new AbstractAction("Selected") {
      @Override
      public void actionPerformed(ActionEvent e) {
        var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        int[] selectedRows = logTable.getSelectedRows();
        StringBuilder sb = new StringBuilder();
        for (int i : selectedRows) {
          sb.append(logTable.getValueAt(i, COLUMN_TIME)).append("\t");
          sb.append(logTable.getValueAt(i, COLUMN_FROM)).append("\t");
          sb.append(logTable.getValueAt(i, COLUMN_TYPE)).append("\t");
          if (parser instanceof GraphicalParser) {
            BufferAccess ba = (BufferAccess) logTable.getValueAt(i, COLUMN_DATA);
            sb.append(ba.getAsHex());
          } else {
            sb.append(logTable.getValueAt(i, COLUMN_DATA));
          }
          sb.append("\t");
          sb.append(logTable.getValueAt(i, COLUMN_SOURCE)).append("\n");
        }
        StringSelection stringSelection = new StringSelection(sb.toString());
        clipboard.setContents(stringSelection, null);
      }
    }));
    popupMenu.add(copyClipboard);
    popupMenu.add(new JMenuItem(clearAction));
    popupMenu.addSeparator();
    popupMenu.add(new JMenuItem(new AbstractAction("Save to file") {
      @Override
      public void actionPerformed(ActionEvent e) {
        JFileChooser fc = new JFileChooser();
        File suggest = new File(Cooja.getExternalToolsSetting("BUFFER_LISTENER_SAVEFILE", "BufferAccessLogger.txt"));
        fc.setSelectedFile(suggest);
        int returnVal = fc.showSaveDialog(Cooja.getTopParentContainer());
        if (returnVal != JFileChooser.APPROVE_OPTION) {
          return;
        }
        File saveFile = fc.getSelectedFile();
        if (saveFile.exists()) {
          String s1 = "Overwrite";
          String s2 = "Cancel";
          Object[] options = {s1, s2};
          int n = JOptionPane.showOptionDialog(
                  Cooja.getTopParentContainer(),
                  "A file with the same name already exists.\nDo you want to remove it?",
                  "Overwrite existing file?", JOptionPane.YES_NO_OPTION,
                  JOptionPane.QUESTION_MESSAGE, null, options, s1);
          if (n != JOptionPane.YES_OPTION) {
            return;
          }
        }
        Cooja.setExternalToolsSetting("BUFFER_LISTENER_SAVEFILE", saveFile.getPath());
        if (saveFile.exists() && !saveFile.canWrite()) {
          logger.error("No write access to file: " + saveFile);
          return;
        }
        try (var outStream = new PrintWriter(Files.newBufferedWriter(saveFile.toPath(), UTF_8))) {
          StringBuilder sb = new StringBuilder();
          for (int i = 0; i < logTable.getRowCount(); i++) {
            sb.append(logTable.getValueAt(i, COLUMN_TIME)).append("\t");
            sb.append(logTable.getValueAt(i, COLUMN_FROM)).append("\t");
            sb.append(logTable.getValueAt(i, COLUMN_TYPE)).append("\t");
            if (parser instanceof GraphicalParser) {
              BufferAccess ba = (BufferAccess) logTable.getValueAt(i, COLUMN_DATA);
              sb.append(ba.getAsHex());
            } else {
              sb.append(logTable.getValueAt(i, COLUMN_DATA));
            }
            sb.append("\t");
            sb.append(logTable.getValueAt(i, COLUMN_SOURCE)).append("\n");
          }
          outStream.print(sb);
        } catch (Exception ex) {
          logger.error("Could not write to file: " + saveFile);
        }
      }
    }));
    popupMenu.addSeparator();
    JMenu focusMenu = new JMenu("Show in");
    focusMenu.add(new JMenuItem(showInAllAction));
    focusMenu.addSeparator();
    focusMenu.add(new JMenuItem(timeLineAction));
    focusMenu.add(new JMenuItem(radioLoggerAction));
    focusMenu.add(new JMenuItem(new AbstractAction("in Buffer Listener") {
      @Override
      public void actionPerformed(ActionEvent e) {
        int view = logTable.getSelectedRow();
        if (view < 0) {
          return;
        }
        int model1 = logTable.convertRowIndexToModel(view);
        long time = logs.get(model1).time;
        simulation.getCooja().getPlugins(BufferListener.class).forEach(p -> p.trySelectTime(time));
      }
    }));
    popupMenu.add(focusMenu);
    popupMenu.addSeparator();
    colorCheckbox = new JCheckBoxMenuItem("Mote-specific coloring");
    popupMenu.add(colorCheckbox);
    colorCheckbox.addActionListener(e -> {
      backgroundColors = colorCheckbox.isSelected();
      repaint();
    });
    inverseFilterCheckbox = new JCheckBoxMenuItem("Inverse filter");
    popupMenu.add(inverseFilterCheckbox);
    inverseFilterCheckbox.addActionListener(e -> {
      inverseFilter = inverseFilterCheckbox.isSelected();
      if (inverseFilter) {
        filterLabel.setText("Exclude:");
      } else {
        filterLabel.setText("Filter:");
      }
      setFilter(getFilter());
      repaint();
    });
    hideReadsCheckbox = new JCheckBoxMenuItem("Hide READs", hideReads);
    popupMenu.add(hideReadsCheckbox);
    hideReadsCheckbox.addActionListener(e -> {
      hideReads = hideReadsCheckbox.isSelected();
      setFilter(getFilter());
      repaint();
    });

    withStackTraceCheckbox = new JCheckBoxMenuItem("Capture stack traces", withStackTrace);
    popupMenu.add(withStackTraceCheckbox);
    withStackTraceCheckbox.addActionListener(e -> {
      withStackTrace = withStackTraceCheckbox.isSelected();
      setFilter(getFilter());
      repaint();
    });

    logTable.setComponentPopupMenu(popupMenu);

    /* Column width adjustment */
    java.awt.EventQueue.invokeLater(() -> {
      /* Make sure this happens *after* adding history */
      adjuster.setDynamicAdjustment(true);
      adjuster.setAdjustColumn(COLUMN_DATA, false);
    });

    logUpdateAggregator.start();
    simulation.getMoteTriggers().addTrigger(this, (event, m) -> {
      if (event == EventTriggers.AddRemove.ADD) {
        try {
          startMonitoring(m);
        } catch (Exception e) {
          logger.warn("Could not monitor buffer on: " + m, e);
        }
      } else {
        stopObserving(m);
      }
    });

    /* UI components */
    JPanel filterPanel = new JPanel();
    filterPanel.setLayout(new BoxLayout(filterPanel, BoxLayout.X_AXIS));
    filterTextField = new JTextField("");
    filterTextFieldBackground = filterTextField.getBackground();
    filterPanel.add(Box.createHorizontalStrut(2));
    filterPanel.add(filterLabel);
    filterPanel.add(filterTextField);
    filterTextField.addActionListener(e -> {
      setFilter(filterTextField.getText());
      // Autoscroll.
      SwingUtilities.invokeLater(() -> {
        int s = logTable.getSelectedRow();
        if (s < 0) {
          return;
        }
        s = logTable.getRowSorter().convertRowIndexToView(s);
        if (s < 0) {
          return;
        }
        int v = logTable.getRowHeight()*s;
        logTable.scrollRectToVisible(new Rectangle(0, v-5, 1, v+5));
      });
    });
    filterPanel.add(Box.createHorizontalStrut(2));

    getContentPane().add(BorderLayout.CENTER, new JScrollPane(logTable));
    getContentPane().add(BorderLayout.SOUTH, filterPanel);

    updateTitle();
    pack();
    setSize(Cooja.getDesktopPane().getWidth(), 150);
    setLocation(0, Cooja.getDesktopPane().getHeight() - 300);
  }

  private void startMonitoring(Mote mote) throws Exception {
    /* If this is a pointer buffer,
     * we must observe both the pointer itself, and the pointed to memory */

    SegmentMemoryMonitor mm = buffer.createMemoryMonitor(this, mote);
    memoryMonitors.add(mm);
    if (!motes.contains(mote)) {
      motes.add(mote);
    }
    updateTitle();
  }

  public enum MemoryMonitorType { SEGMENT, POINTER }

  static class PointerMemoryMonitor extends SegmentMemoryMonitor {
    private SegmentMemoryMonitor segmentMonitor;
    private long lastSegmentAddress = -1;
    private final long pointerAddress;
    private final int pointerSize;

    public PointerMemoryMonitor(
        BufferListener bl, Mote mote,
        long pointerAddress, int pointerSize, int segmentSize)
    throws Exception {
      super(bl, mote, pointerAddress, pointerSize);
      this.pointerAddress = pointerAddress;
      this.pointerSize = pointerSize;

      registerSegmentMonitor(segmentSize, false);
    }

    private void registerSegmentMonitor(int size, boolean notify) throws Exception {
      byte[] pointerValue = mote.getMemory().getMemorySegment(pointerAddress, pointerSize);
      long segmentAddress = MemoryBuffer.wrap(mote.getMemory().getLayout(), pointerValue).getAddr();

      segmentMonitor = new SegmentMemoryMonitor(bl, mote, segmentAddress, size);
      if (notify) {
        segmentMonitor.memoryChanged(mote.getMemory(), EventType.WRITE, -1);
      }
      lastSegmentAddress = segmentAddress;
    }

    @Override
    final public void memoryChanged(MemoryInterface memory,
        EventType type, long address) {
      if (type == EventType.READ) {
        return;
      }

      byte[] pointerValue = mote.getMemory().getMemorySegment(pointerAddress, pointerSize);
      long segmentAddress = MemoryBuffer.wrap(mote.getMemory().getLayout(), pointerValue).getAddr();
      if (segmentAddress == lastSegmentAddress) {
        return;
      }

      /* Pointer changed - we need to create new segment monitor */
      segmentMonitor.dispose();
      try {
        registerSegmentMonitor(segmentMonitor.getSize(), true);
      } catch (Exception e) {
        logger.warn("Could not re-register memory monitor on: " + mote, e);
      }
    }

    @Override
    public MemoryMonitorType getType() {
      return MemoryMonitorType.POINTER;
    }

    @Override
    public void dispose() {
      super.dispose();
      segmentMonitor.dispose();
    }
  }

  static class SegmentMemoryMonitor implements SegmentMonitor {
    final BufferListener bl;
    final Mote mote;

    private final long address;
    private final int size;

    private byte[] oldData;

    SegmentMemoryMonitor(BufferListener bl, Mote mote, long address, int size)
    throws Exception {
      this.bl = bl;
      this.mote = mote;
      this.address = address;
      this.size = size;

      if (address > 0) {
        if (!mote.getMemory().addSegmentMonitor(SegmentMonitor.EventType.WRITE, address, size, this)) {
          throw new Exception("Could not register memory monitor on: " + mote);
        }
      }
    }

    Mote getMote() {
      return mote;
    }
    long getAddress() {
      return address;
    }
    int getSize() {
      return size;
    }
    public MemoryMonitorType getType() {
      return MemoryMonitorType.SEGMENT;
    }

    void dispose() {
      if (address > 0) {
        mote.getMemory().removeSegmentMonitor(address, size, this);
      }
    }

    @Override
    public void memoryChanged(MemoryInterface memory, EventType type, long address) {
      byte[] newData = getAddress()<=0?null:mote.getMemory().getMemorySegment(getAddress(), getSize());
      addBufferAccess(bl, mote, oldData, newData, type, this.address);
      oldData = newData;
    }

    static void addBufferAccess(BufferListener bl, Mote mote, byte[] oldData, byte[] newData, EventType type, long address) {
      BufferAccess ba = new BufferAccess(
          mote,
          mote.getSimulation().getSimulationTime(),
          address,
          newData,
          oldData,
          type,
          bl.withStackTrace
      );
      bl.logUpdateAggregator.add(ba);
    }
  }

  private void stopObserving(Mote mote) {
    for (SegmentMemoryMonitor mm: memoryMonitors.toArray(new SegmentMemoryMonitor[0])) {
      if (mm.getMote() == mote) {
        mm.dispose();
        memoryMonitors.remove(mm);
      }
    }

    motes.remove(mote);
    updateTitle();
  }

  private void repaintTimeColumn() {
    logTable.getColumnModel().getColumn(COLUMN_TIME).setHeaderValue(
        logTable.getModel().getColumnName(COLUMN_TIME));
    repaint();
  }

  private void updateTitle() {
    if (buffer != null) {
      String status = buffer.getStatusString();
      setTitle("Buffer Listener - " +
          ((status!=null)?status:Cooja.getDescriptionOf(buffer)) + " " +
          "- " + memoryMonitors.size() + " buffers on " + motes.size() + " motes");
    }
  }

  @Override
  public void closePlugin() {
    if (hourTimeEvent != null) hourTimeEvent.remove();

    /* Stop observing motes */
    logUpdateAggregator.stop();
    simulation.getMoteTriggers().deleteTriggers(this);
    for (Mote m: simulation.getMotes()) {
      stopObserving(m);
    }
  }

  @Override
  public Collection<Element> getConfigXML() {
    ArrayList<Element> config = new ArrayList<>();
    Element element;

    element = new Element("filter");
    element.setText(filterTextField.getText());
    config.add(element);

    if (formatTimeString) {
      element = new Element("formatted_time");
      config.add(element);
    }
    if (backgroundColors) {
      element = new Element("coloring");
      config.add(element);
    }
    if (inverseFilter) {
      element = new Element("inversefilter");
      config.add(element);
    }
    if (!hideReads) {
      element = new Element("showreads");
      config.add(element);
    }
    if (withStackTrace) {
      element = new Element("stacktrace");
      config.add(element);
    }
    element = new Element("parser");
    element.setText(parser.getClass().getName());
    config.add(element);

    element = new Element("buffer");
    element.setText(buffer.getClass().getName());
    buffer.writeConfig(element);
    config.add(element);

    return config;
  }

  @Override
  public boolean setConfigXML(Collection<Element> configXML, boolean visAvailable) {
    for (Element element : configXML) {
      String name = element.getName();
      if ("filter".equals(name)) {
        final String str = element.getText();
        EventQueue.invokeLater(() -> setFilter(str));
      } else if ("coloring".equals(name)) {
        backgroundColors = true;
        colorCheckbox.setSelected(true);
      } else if ("inversefilter".equals(name)) {
        inverseFilter = true;
        inverseFilterCheckbox.setSelected(true);
      } else if ("showreads".equals(name)) {
        hideReads = false;
        hideReadsCheckbox.setSelected(false);
      } else if ("stacktrace".equals(name)) {
        withStackTrace = true;
        withStackTraceCheckbox.setSelected(true);
      } else if ("formatted_time".equals(name)) {
        formatTimeString = true;
        repaintTimeColumn();
      } else if ("parser".equals(name)) {
        String parserClassname = element.getText();
        Class<? extends Parser> parserClass =
          simulation.getCooja().tryLoadClass(this, Parser.class, parserClassname);
        if (parserClass == null) {
          logger.warn("Could not create buffer parser: could not find class: " + parserClassname);
        } else {
          setParser(parserClass);
        }
      } else if ("buffer".equals(name)) {
        String bufferClassname = element.getText();
        Class<? extends Buffer> btClass =
          simulation.getCooja().tryLoadClass(this, Buffer.class, bufferClassname);
        if (btClass == null) {
          logger.warn("Could not create buffer parser: could not find class: " + bufferClassname);
        } else {
          Buffer b = createBufferInstance(btClass);
          if (b != null) {
            b.applyConfig(element);
            setBuffer(b);
          }
        }
      }
    }

    return true;
  }

  private String getFilter() {
    return filterTextField.getText();
  }

  private void setFilter(String str) {
    filterTextField.setText(str);

    try {
      final RowFilter<Object,Object> regexp;
      if (str != null && !str.isEmpty()) {
        /* TODO Handle graphical components */
        regexp = RowFilter.regexFilter(str, COLUMN_FROM, COLUMN_TYPE, COLUMN_SOURCE, COLUMN_DATA);
      } else {
        regexp = null;
      }
      RowFilter<Object, Object> wrapped = new RowFilter<>() {
        @Override
        public boolean include(RowFilter.Entry<?, ?> entry) {
          if (hideReads) {
            int row = (Integer) entry.getIdentifier();
            if (logs.get(row).type == SegmentMonitor.EventType.READ) {
              return false;
            }
          }
          if (regexp != null) {
            boolean pass = regexp.include(entry);

            if (inverseFilter && pass) {
              return false;
            } else return inverseFilter || pass;
          }
          return true;
        }
      };
      logFilter.setRowFilter(wrapped);
      filterTextField.setBackground(filterTextFieldBackground);
      filterTextField.setToolTipText(null);
    } catch (PatternSyntaxException e) {
      logFilter.setRowFilter(null);
      filterTextField.setBackground(Color.red);
      filterTextField.setToolTipText("Syntax error in regular expression: " + e.getMessage());
    }
  }

  public void trySelectTime(final long time) {
    java.awt.EventQueue.invokeLater(() -> {
      for (int i=0; i < logs.size(); i++) {
        if (logs.get(i).time < time) {
          continue;
        }

        int view = logTable.convertRowIndexToView(i);
        if (view < 0) {
          continue;
        }
        logTable.scrollRectToVisible(logTable.getCellRect(view, 0, true));
        logTable.setRowSelectionInterval(view, view);
        return;
      }
    });
  }

  public static class BufferAccess {
    public static final byte[] NULL_DATA = new byte[0];

    public final Mote mote;
    public final long time;

    public final byte[] mem;
    private boolean[] accessedBitpattern;

    public final SegmentMonitor.EventType type;
    public final String sourceStr;
    public final String stackTrace;
    public final long address;

    public BufferAccess(
        Mote mote, long time, long address, byte[] newData, byte[] oldData, SegmentMonitor.EventType type, boolean withStackTrace) {
      this.mote = mote;
      this.time = time;
      this.mem = newData==null?NULL_DATA:newData;
      this.type = type;
      this.address = address;

      /* Generate diff bit pattern */
      if (newData != null && oldData != null) {
        accessedBitpattern = new boolean[newData.length];
        for (int i=0; i < newData.length; i++) {
          accessedBitpattern[i] = (oldData[i] != mem[i]);
        }
      } else if (newData != null) {
        accessedBitpattern = new boolean[newData.length];
        Arrays.fill(accessedBitpattern, true);
      }

      if (mote instanceof AbstractEmulatedMote<?, ?, ?> emulatedMote) {
        String s = emulatedMote.getPCString();
        sourceStr = s==null?"[unknown]":s;
        stackTrace = withStackTrace ? emulatedMote.getStackTrace() : null;
      } else {
        this.sourceStr = "[unknown]";
        this.stackTrace = null;
      }
    }

    public String getAsHex() {
      return String.format("%016x", address) + ":" + StringUtils.toHex(mem);
    }

    public boolean[] getAccessedBitpattern() {
      return accessedBitpattern;
    }

    public String getType() {
      return type.toString();
    }

    public Object getSource() {
      return sourceStr;
    }

    public String getID() {
      return "ID:" + mote.getID();
    }

    public String getTime(boolean formatTimeString, boolean hasHours) {
      if (formatTimeString) {
        long t = time;
        long h = (t / TIME_HOUR);
        t -= (t / TIME_HOUR)*TIME_HOUR;
        long m = (t / TIME_MINUTE);
        t -= (t / TIME_MINUTE)*TIME_MINUTE;
        long s = (t / TIME_SECOND);
        t -= (t / TIME_SECOND)*TIME_SECOND;
        long ms = t / Simulation.MILLISECOND;
        if (hasHours) {
          return String.format("%d:%02d:%02d.%03d", h,m,s,ms);
        } else {
          return String.format("%02d:%02d.%03d", m,s,ms);
        }
      } else {
        return String.valueOf(time / Simulation.MILLISECOND);
      }
    }
  }

  private final Action timeLineAction = new AbstractAction("in Timeline") {
    @Override
    public void actionPerformed(ActionEvent e) {
      int view = logTable.getSelectedRow();
      if (view < 0) {
        return;
      }
      int model = logTable.convertRowIndexToModel(view);
      long time = logs.get(model).time;

      simulation.getCooja().getPlugins(TimeLine.class).forEach(p -> p.trySelectTime(time));
    }
  };

  private final Action radioLoggerAction = new AbstractAction("in Radio Logger") {
    @Override
    public void actionPerformed(ActionEvent e) {
      int view = logTable.getSelectedRow();
      if (view < 0) {
        return;
      }
      int model = logTable.convertRowIndexToModel(view);
      long time = logs.get(model).time;

      simulation.getCooja().getPlugins(RadioLogger.class).forEach(p -> p.trySelectTime(time));
    }
  };

  private final Action showInAllAction = new AbstractAction("All") {
    {
      putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, true));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      timeLineAction.actionPerformed(null);
      radioLoggerAction.actionPerformed(null);
    }
  };

  private final Action clearAction = new AbstractAction("Clear") {
    @Override
    public void actionPerformed(ActionEvent e) {
      int size = logs.size();
      if (size > 0) {
        logs.clear();
        model.fireTableRowsDeleted(0, size - 1);
      }
    }
  };

  private void setParser(Class<? extends Parser> bpClass) {
    Parser bp;
    try {
      bp = bpClass.getDeclaredConstructor().newInstance();
    } catch (InvocationTargetException | InstantiationException | IllegalAccessException | NoSuchMethodException e) {
      logger.warn("Could not create buffer parser: " + e.getMessage(), e);
      return;
    }

    parser = bp;
    logTable.getColumnModel().getColumn(COLUMN_DATA).setHeaderValue(Cooja.getDescriptionOf(bp));

    repaint();
  }

  private static class BufferInput {
    private final JPanel mainPanel = new JPanel();
    private final JTextField textName = new JTextField();
    private final JTextField textSize = new JTextField();
    private final JTextField textOffset = new JTextField();

    public BufferInput(String name, String size, String offset) {
      mainPanel.setLayout(new GridLayout(3, 2, 5, 5));

      if (name != null) {
        textName.setText(name);
        mainPanel.add(new JLabel("Symbol:"));
        mainPanel.add(textName);
      }
      if (size != null) {
        textSize.setText(size);
        mainPanel.add(new JLabel("Size (1-" + MAX_BUFFER_SIZE + "):"));
        mainPanel.add(textSize);
      }
      if (offset != null) {
        textOffset.setText(offset);
        mainPanel.add(new JLabel("Offset"));
        mainPanel.add(textOffset);
      }
    }
    public String getName() {
      return textName.getText();
    }
    public String getSize() {
      return textSize.getText();
    }
    public String getOffset() {
      return textOffset.getText();
    }
    public JComponent getComponent() {
      return mainPanel;
    }
  }

  private static Buffer createBufferInstance(Class<? extends Buffer> btClass) {
    try {
      return btClass.getDeclaredConstructor().newInstance();
    } catch (InvocationTargetException | InstantiationException | IllegalAccessException | NoSuchMethodException e) {
      logger.warn("Could not create buffer type: " + e.getMessage(), e);
      return null;
    }
  }

  private void setBuffer(Buffer buffer) {
    if (buffer == null) {
      return;
    }

    this.buffer = buffer;

    /* Reregister memory monitors */
    for (Mote m: simulation.getMotes()) {
      stopObserving(m);
    }
    for (Mote m: simulation.getMotes()) {
      try {
        startMonitoring(m);
      } catch (Exception e) {
        logger.warn("Could not monitor buffer on: " + m, e);
      }
    }

    /* Clear previous buffers, update gui */
    clearAction.actionPerformed(null);
    updateTitle();
    repaint();
  }

  public interface Parser {
    /**
     * @param ba Buffer Access object
     * @return String or custom graphical object
     */
    Object parse(BufferAccess ba);
  }
  public static abstract class GraphicalParser implements Parser {
    BufferAccess ba;
    @Override
    public Object parse(BufferAccess ba) {
      this.ba = ba;
      return ba;
    }
    public abstract void paintComponent(Graphics g, JComponent c);
    public abstract int getUnscaledWidth();
  }

  public static abstract class StringParser implements Parser {
    @Override
    public Object parse(BufferAccess ba) {
      return parseString(ba);
    }

    public abstract String parseString(BufferAccess ba);
  }

  public interface Buffer {
    long getAddress(Mote mote);
    int getSize(Mote mote);

    String getStatusString();

    SegmentMemoryMonitor createMemoryMonitor(BufferListener bl, Mote mote)
    throws Exception;

    /*
     * Called when buffer is created by user to allow user input (AWT thread)
     */
    boolean configure(BufferListener bl);

    /*
     * Called when buffer is created from config
     */
    void applyConfig(Element element);

    void writeConfig(Element element);
  }
  public static abstract class AbstractBuffer implements Buffer {
    @Override
    public String getStatusString() {
      return null;
    }
    @Override
    public void writeConfig(Element element) {
    }
    @Override
    public void applyConfig(Element element) {
    }
    @Override
    public boolean configure(BufferListener bl) {
      return true;
    }
  }

  public static abstract class PointerBuffer extends AbstractBuffer {
    public abstract long getPointerAddress(Mote mote);

    @Override
    public SegmentMemoryMonitor createMemoryMonitor(BufferListener bl, Mote mote)
    throws Exception {
      return new PointerMemoryMonitor(
          bl,
          mote,
          getPointerAddress(mote),
          mote.getMemory().getLayout().addrSize,
          getSize(mote)
      );
    }
  }
  public static abstract class SegmentBuffer extends AbstractBuffer {
    @Override
    public SegmentMemoryMonitor createMemoryMonitor(BufferListener bl, Mote mote)
    throws Exception {
      return new SegmentMemoryMonitor(
          bl,
          mote,
          getAddress(mote),
          getSize(mote)
      );
    }
  }

  public static boolean registerBufferParser(Class<? extends Parser> bpClass) {
    if (bufferParsers.contains(bpClass)) {
      return false;
    }
    bufferParsers.add(bpClass);
    return true;
  }
  public static void unregisterBufferParser(Class<? extends Parser> bpClass) {
    bufferParsers.remove(bpClass);
  }

  public static boolean registerBufferType(Class<? extends Buffer> btClass) {
    if (bufferTypes.contains(btClass)) {
      return false;
    }
    bufferTypes.add(btClass);
    return true;
  }
  public static void unregisterBufferType(Class<? extends Buffer> btClass) {
    bufferTypes.remove(btClass);
  }

  @ClassDescription("Byte array")
  public static class ByteArrayParser extends StringParser {
    @Override
    public String parseString(BufferAccess ba) {
      boolean[] diff = ba.getAccessedBitpattern();
      if (diff == null) {
        return StringUtils.toHex(ba.mem, 4); /* 00112233 00112233 .. */
      }
      StringBuilder sb = new StringBuilder();
      sb.append("<html>");
      boolean inRed = false;
      int group = 0;
      for (int i=0; i < ba.mem.length; i++) {
        if (inRed == diff[i]) {
          sb.append(StringUtils.toHex(ba.mem[i]));
        } else if (!inRed) {
          /* Diff begins */
          sb.append("<font color=\"red\">");
          sb.append(StringUtils.toHex(ba.mem[i]));
          inRed = true;
        } else {
          /* Diff ends */
          sb.append("</font>");
          sb.append(StringUtils.toHex(ba.mem[i]));
          inRed = false;
        }
        group++;
        if (++group >= 8) {
          group=0;
          sb.append(" ");
        }
      }
      if (inRed) {
        /* Diff ends */
        sb.append("</font>");
      }
      sb.append("</html>");
      return sb.toString();
    }
  }

  @ClassDescription("Integer array")
  public static class IntegerParser extends StringParser {
    private final VarMemory varMem = new VarMemory(null);
    @Override
    public String parseString(BufferAccess ba) {
      StringBuilder sb = new StringBuilder();
      varMem.associateMemory(ba.mote.getMemory());

      int intLen = ba.mote.getMemory().getLayout().intSize;
      sb.append("<html>");
      for (int i=0; i < ba.mem.length/intLen; i++) {
        byte[] mem = Arrays.copyOfRange(ba.mem, i*intLen,(i+1)*intLen);
        boolean[] diff = Arrays.copyOfRange(ba.getAccessedBitpattern(), i*intLen,(i+1)*intLen);
        int val = MemoryBuffer.wrap(ba.mote.getMemory().getLayout(), mem).getInt();

        boolean red = false;
        for (boolean changed: diff) {
          if (changed) {
            red = true;
            break;
          }
        }

        if (red) {
          sb.append("<font color=\"red\">");
        }
        sb.append(val).append(" ");
        if (red) {
          sb.append("</font>");
        }
      }
      sb.append("</html>");
      return sb.toString();
    }
  }

  @ClassDescription("Terminated string")
  public static class TerminatedStringParser extends StringParser {
    @Override
    public String parseString(BufferAccess ba) {
      /* TODO Diff? */
      int i;
      for (i=0; i < ba.mem.length; i++) {
        if (ba.mem[i] == '\0') {
          break;
        }
      }
      byte[] termString = new byte[i];
      System.arraycopy(ba.mem, 0, termString, 0, i);
      return new String(termString, UTF_8).replaceAll("[^\\p{Print}]", "");
    }
  }

  @ClassDescription("Printable characters")
  public static class PrintableCharactersParser extends StringParser {
    @Override
    public String parseString(BufferAccess ba) {
      /* TODO Diff? */
      return new String(ba.mem, UTF_8).replaceAll("[^\\p{Print}]", "");
    }
  }

  @ClassDescription("IPv6 address")
  public static class IPv6AddressParser extends StringParser {
    @Override
    public String parseString(BufferAccess ba) {
      /* TODO Diff? */
      if (ba.mem.length < 16) {
        return "[must monitor at least 16 bytes]";
      }
      byte[] mem;
      if (ba.mem.length > 16) {
        mem = new byte[16];
        System.arraycopy(ba.mem, 0, mem, 0, 16);
      } else {
        mem = ba.mem;
      }
      return IPUtils.getCompressedIPv6AddressString(mem);
    }
  }

  static class GrapicalParserPanel extends JPanel {
    static final int XOFFSET = 0;
    static final int HEIGHT = 16;
    private GraphicalParser parser;
    public GrapicalParserPanel() {
      super();
    }
    public void update(BufferAccess ba, GraphicalParser parser) {
      this.parser = parser;
      parser.ba = ba;
      setPreferredSize(new Dimension(parser.getUnscaledWidth() + 2*XOFFSET, HEIGHT));
    }
    @Override
    public void paintComponent(Graphics g) {
      super.paintComponent(g);
      g.translate(XOFFSET, 0);

      if (getWidth() > getPreferredSize().width + 10 ||
          getWidth() < getPreferredSize().width - 10) {
        double scale = 1.0*getWidth()/getPreferredSize().width;
        ((Graphics2D)g).scale(scale, 1.0);
      }

      parser.paintComponent(g, this);
    }
  }

  private final GrapicalParserPanel graphicalParserPanel = new GrapicalParserPanel();

  @ClassDescription("Graphical: Height")
  public static class GraphicalHeight4BitsParser extends GraphicalParser {
    @Override
    public int getUnscaledWidth() {
      return ba.mem.length*2;
    }
    @Override
    public void paintComponent(Graphics g, JComponent c) {
      g.setColor(Color.GRAY);
      boolean[] diff = ba.getAccessedBitpattern();
      for (int x=0; x < ba.mem.length; x++) {
        boolean red = diff != null && diff[x];
        int v = 0xff&ba.mem[x];
        int h = Math.min(v/16, 15); /* crop */
        if (red) {
          g.setColor(Color.RED);
        }
        g.fillRect(x*2, 16-h, 2, h);
        if (red) {
          g.setColor(Color.GRAY);
        }
      }
    }
  }

  @ClassDescription("Graphical: Grayscale")
  public static class GraphicalGrayscale4BitsParser extends GraphicalParser {
    @Override
    public int getUnscaledWidth() {
      return ba.mem.length*2;
    }
    @Override
    public void paintComponent(Graphics g, JComponent c) {
      boolean[] diff = ba.getAccessedBitpattern();
      for (int x=0; x < ba.mem.length; x++) {
        boolean red = diff != null && diff[x];
        int color = 255-(0xff&ba.mem[x]);
        if (red) {
          g.setColor(Color.RED);
        } else {
          g.setColor(new Color(color, color, color));
        }
        g.fillRect(x*2, 1, 2, 15);
      }
    }
  }

  @ClassDescription("Variable: node_id")
  public static class NodeIDBuffer extends SegmentBuffer {
    @Override
    public long getAddress(Mote mote) {
      if (!mote.getMemory().getSymbolMap().containsKey("node_id")) {
        return -1;
      }
      return mote.getMemory().getSymbolMap().get("node_id").addr;
    }
    @Override
    public int getSize(Mote mote) {
      return mote.getMemory().getLayout().intSize;
    }

  }

  @ClassDescription("Queuebuf 0 RAM")
  public static class Queuebuf0Buffer extends SegmentBuffer {
    @Override
    public long getAddress(Mote mote) {
      if (!mote.getMemory().getSymbolMap().containsKey("buframmem")) {
        return -1;
      }
      long offset = 0;
      return mote.getMemory().getSymbolMap().get("buframmem").addr + offset;
    }
    @Override
    public int getSize(Mote mote) {
      return 128;
    }
  }

  @ClassDescription("packetbuf_aligned")
  public static class PacketbufBuffer extends SegmentBuffer {
    @Override
    public long getAddress(Mote mote) {
      if (!mote.getMemory().getSymbolMap().containsKey("packetbuf_aligned")) {
        return -1;
      }
      return mote.getMemory().getSymbolMap().get("packetbuf_aligned").addr;
    }
    @Override
    public int getSize(Mote mote) {
      return 128;
    }
  }

  @ClassDescription("*packetbufptr")
  public static class PacketbufPointerBuffer extends PointerBuffer {
    final VarMemory varMem =  new VarMemory(null);
    @Override
    public long getPointerAddress(Mote mote) {
      if (!mote.getMemory().getSymbolMap().containsKey("packetbufptr")) {
        return -1;
      }
      return mote.getMemory().getSymbolMap().get("packetbufptr").addr;
    }
    @Override
    public long getAddress(Mote mote) {
      if (!mote.getMemory().getSymbolMap().containsKey("packetbufptr")) {
        return -1;
      }
      varMem.associateMemory(mote.getMemory());
      return varMem.getAddrValueOf("packetbufptr");
    }
    @Override
    public int getSize(Mote mote) {
      return 128;
    }
  }

  @ClassDescription("Pointer...")
  public static class CustomPointerBuffer extends PointerBuffer {
    public String variable;
    public int size;
    public long offset;
    final VarMemory varMem =  new VarMemory(null);
    @Override
    public long getPointerAddress(Mote mote) {
      if (!mote.getMemory().getSymbolMap().containsKey(variable)) {
        return -1;
      }
      return mote.getMemory().getSymbolMap().get(variable).addr;
    }
    @Override
    public long getAddress(Mote mote) {
      if (!mote.getMemory().getSymbolMap().containsKey(variable)) {
        return -1;
      }
      varMem.associateMemory(mote.getMemory());
      return varMem.getAddrValueOf(variable)+offset;
    }
    @Override
    public int getSize(Mote mote) {
      if (!mote.getMemory().getSymbolMap().containsKey(variable)) {
        return -1;
      }
      return size;
    }

    @Override
    public String getStatusString() {
      if (offset > 0) {
        return "Pointer *" + variable + "[" + offset + "] (" + size + ")";
      } else {
        return "Pointer *" + variable + " (" + size + ")";
      }
    }

    @Override
    public void writeConfig(Element element) {
      element.setAttribute("variable", variable);
      element.setAttribute("size", String.valueOf(size));
      element.setAttribute("offset", String.valueOf(offset));
    }
    @Override
    public void applyConfig(Element element) {
      variable = element.getAttributeValue("variable");
      size = Integer.parseInt(element.getAttributeValue("size"));
      offset = Long.parseLong(element.getAttributeValue("offset"));
    }
    @Override
    public boolean configure(BufferListener bl) {
      String suggestName = Cooja.getExternalToolsSetting("BUFFER_LISTENER_VARNAME", "node_id");
      String suggestSize = Cooja.getExternalToolsSetting("BUFFER_LISTENER_VARSIZE", "2");
      String suggestOffset = Cooja.getExternalToolsSetting("BUFFER_LISTENER_VAROFFSET", "0");
      BufferInput infoComponent =
        new BufferInput(suggestName, suggestSize, suggestOffset);

      int result = JOptionPane.showConfirmDialog(bl,
          infoComponent.getComponent(),
          "Symbol info",
          JOptionPane.OK_CANCEL_OPTION);
      if (result != JOptionPane.OK_OPTION) {
        /* Abort */
        return false;
      }
      variable = infoComponent.getName();
      if (variable == null) {
        return false;
      }
      try {
        size = Integer.parseInt(infoComponent.getSize());
        if (size < 1 || size > MAX_BUFFER_SIZE) {
          /* Abort */
          logger.error("Bad buffer size " + infoComponent.getSize() + ": min 1, max " + MAX_BUFFER_SIZE);
          return false;
        }
      } catch (RuntimeException e) {
        logger.error("Failed parsing buffer size " + infoComponent.getSize() + ": " + e.getMessage(), e);
        return false;
      }
      try {
        offset = Long.parseLong(infoComponent.getOffset());
      } catch (RuntimeException e) {
        logger.error("Failed parsing buffer offset " + infoComponent.getOffset() + ": " + e.getMessage(), e);
        /* Abort */
        return false;
      }

      Cooja.setExternalToolsSetting("BUFFER_LISTENER_VARNAME", variable);
      Cooja.setExternalToolsSetting("BUFFER_LISTENER_VARSIZE", String.valueOf(size));
      Cooja.setExternalToolsSetting("BUFFER_LISTENER_VAROFFSET", String.valueOf(offset));
      return true;
    }
  }

  @ClassDescription("Symbol...")
  public static class CustomVariableBuffer extends SegmentBuffer {
    public String variable;
    public int size;
    public long offset;
    @Override
    public long getAddress(Mote mote) {
      if (!mote.getMemory().getSymbolMap().containsKey(variable)) {
        return -1;
      }
      return mote.getMemory().getSymbolMap().get(variable).addr+offset;
    }
    @Override
    public int getSize(Mote mote) {
      if (!mote.getMemory().getSymbolMap().containsKey(variable)) {
        return -1;
      }
      return size;
    }

    @Override
    public String getStatusString() {
      if (offset > 0) {
        return "Symbol &" + variable + "[" + offset + "] (" + size + ")";
      } else {
        return "Symbol " + variable + " (" + size + ")";
      }
    }

    @Override
    public void writeConfig(Element element) {
      element.setAttribute("variable", variable);
      element.setAttribute("size", String.valueOf(size));
      element.setAttribute("offset", String.valueOf(offset));
    }
    @Override
    public void applyConfig(Element element) {
      variable = element.getAttributeValue("variable");
      size = Integer.parseInt(element.getAttributeValue("size"));
      offset = Long.parseLong(element.getAttributeValue("offset"));
    }
    @Override
    public boolean configure(BufferListener bl) {
      String suggestName = Cooja.getExternalToolsSetting("BUFFER_LISTENER_VARNAME", "node_id");
      String suggestSize = Cooja.getExternalToolsSetting("BUFFER_LISTENER_VARSIZE", "2");
      String suggestOffset = Cooja.getExternalToolsSetting("BUFFER_LISTENER_VAROFFSET", "0");
      BufferInput infoComponent =
        new BufferInput(suggestName, suggestSize, suggestOffset);

      int result = JOptionPane.showConfirmDialog(bl,
          infoComponent.getComponent(),
          "Symbol info",
          JOptionPane.OK_CANCEL_OPTION);
      if (result != JOptionPane.OK_OPTION) {
        /* Abort */
        return false;
      }
      variable = infoComponent.getName();
      if (variable == null) {
        return false;
      }
      try {
        size = Integer.parseInt(infoComponent.getSize());
        if (size < 1 || size > MAX_BUFFER_SIZE) {
          /* Abort */
          logger.error("Bad buffer size " + infoComponent.getSize() + ": min 1, max " + MAX_BUFFER_SIZE);
          return false;
        }
      } catch (RuntimeException e) {
        logger.error("Failed parsing buffer size " + infoComponent.getSize() + ": " + e.getMessage(), e);
        return false;
      }
      try {
        offset = Long.parseLong(infoComponent.getOffset());
      } catch (RuntimeException e) {
        logger.error("Failed parsing buffer offset " + infoComponent.getOffset() + ": " + e.getMessage(), e);
        /* Abort */
        return false;
      }
      Cooja.setExternalToolsSetting("BUFFER_LISTENER_VARNAME", variable);
      Cooja.setExternalToolsSetting("BUFFER_LISTENER_VARSIZE", String.valueOf(size));
      Cooja.setExternalToolsSetting("BUFFER_LISTENER_VAROFFSET", String.valueOf(offset));
      return true;
    }
  }

  @ClassDescription("Integer...")
  public static class CustomIntegerBuffer extends SegmentBuffer {
    public String variable;
    @Override
    public long getAddress(Mote mote) {
      if (!mote.getMemory().getSymbolMap().containsKey(variable)) {
        return -1;
      }
      return mote.getMemory().getSymbolMap().get(variable).addr;
    }
    @Override
    public int getSize(Mote mote) {
      return mote.getMemory().getLayout().intSize;
    }

    @Override
    public String getStatusString() {
      return "Integer " + variable;
    }

    @Override
    public void writeConfig(Element element) {
      element.setAttribute("variable", variable);
    }
    @Override
    public void applyConfig(Element element) {
      variable = element.getAttributeValue("variable");
    }
    @Override
    public boolean configure(BufferListener bl) {
      String suggestName = Cooja.getExternalToolsSetting("BUFFER_LISTENER_VARNAME", "node_id");
      BufferInput infoComponent =
        new BufferInput(suggestName, null, null);

      int result = JOptionPane.showConfirmDialog(bl,
          infoComponent.getComponent(),
          "Symbol info",
          JOptionPane.OK_CANCEL_OPTION);
      if (result != JOptionPane.OK_OPTION) {
        /* Abort */
        return false;
      }
      variable = infoComponent.getName();
      if (variable == null) {
        return false;
      }
      Cooja.setExternalToolsSetting("BUFFER_LISTENER_VARNAME", variable);
      return true;
    }
  }

}
