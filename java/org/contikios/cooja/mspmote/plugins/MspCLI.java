/*
 * Copyright (c) 2007, Swedish Institute of Computer Science.
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
 *
 */

package org.contikios.cooja.mspmote.plugins;
import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.PrintStream;
import java.util.List;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import org.contikios.cooja.ClassDescription;
import org.contikios.cooja.Cooja;
import org.contikios.cooja.HasQuickHelp;
import org.contikios.cooja.Mote;
import org.contikios.cooja.MotePlugin;
import org.contikios.cooja.PluginType;
import org.contikios.cooja.Simulation;
import org.contikios.cooja.SupportedArguments;
import org.contikios.cooja.VisPlugin;
import org.contikios.cooja.dialogs.UpdateAggregator;
import org.contikios.cooja.mspmote.MspMote;
import se.sics.mspsim.cli.CommandContext;
import se.sics.mspsim.cli.LineOutputStream;

@ClassDescription("Msp CLI")
@PluginType(PluginType.PType.MOTE_PLUGIN)
@SupportedArguments(motes = {MspMote.class})
public class MspCLI extends VisPlugin implements MotePlugin, HasQuickHelp {
  private final MspMote mspMote;
  private final String[] history = new String[50];
  private int historyPos;
  private int historyCount;
  private static final int UPDATE_INTERVAL = 250;
  private final UpdateAggregator<String> cliResponseAggregator;

  public MspCLI(Mote mote, Simulation simulationToVisualize, Cooja gui) {
    super("Msp CLI (" + mote.getID() + ')', gui);
    this.mspMote = (MspMote) mote;

    final Container panel = getContentPane();

    final var logArea = new JTextArea(4, 20);
    logArea.setTabSize(8);
    logArea.setEditable(false);
    panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
    cliResponseAggregator = new UpdateAggregator<>(UPDATE_INTERVAL) {
      @Override
      protected void handle(List<String> ls) {
        String current = logArea.getText();
        int len = current.length();
        if (len > 4096) {
          current = current.substring(len - 4096);
        }

        /* Add */
        var sb = new StringBuilder(current);
        for (String l : ls) {
          sb.append(l).append('\n');
        }
        logArea.setText(sb.toString());
        logArea.setCaretPosition(sb.length());
      }
    };

    PrintStream po = new PrintStream(new LineOutputStream(cliResponseAggregator::add));
    final CommandContext commandContext = new CommandContext(mspMote.getCLICommandHandler(), null, "", new String[0], 1, null);
    commandContext.out = po;
    commandContext.err = po;

    JPopupMenu popupMenu = new JPopupMenu();
    JMenuItem clearItem = new JMenuItem("Clear");
    clearItem.addActionListener(e -> logArea.setText(""));
    popupMenu.add(clearItem);
    logArea.setComponentPopupMenu(popupMenu);

    final var commandField = new JTextField();
    commandField.addActionListener(e -> {
      String command = trim(commandField.getText());
      if (command != null) {
        int previous = historyCount - 1;
        if (previous < 0) {
          previous += history.length;
        }
        try {
          if (!command.equals(history[previous])) {
            history[historyCount] = command;
            historyCount = (historyCount + 1) % history.length;
          }
          historyPos = historyCount;
          cliResponseAggregator.add("> " + command);

          mspMote.executeCLICommand(command, commandContext);
          commandField.setText("");
        } catch (Exception ex) {
          System.err.println("could not send '" + command + "':");
          JOptionPane.showMessageDialog(panel,
              "could not send '" + command + "':\n"
              + ex, "ERROR",
              JOptionPane.ERROR_MESSAGE);
        }
      } else {
        commandField.getToolkit().beep();
      }
    });
    commandField.addKeyListener(new KeyAdapter() {

      @Override
      public void keyPressed(KeyEvent e) {
        switch (e.getKeyCode()) {
          case KeyEvent.VK_UP -> {
            int nextPos = (historyPos + history.length - 1) % history.length;
            if (nextPos == historyCount || history[nextPos] == null) {
              commandField.getToolkit().beep();
            } else {
              String cmd = trim(commandField.getText());
              if (cmd != null) {
                history[historyPos] = cmd;
              }
              historyPos = nextPos;
              commandField.setText(history[historyPos]);
            }
          }
          case KeyEvent.VK_DOWN -> {
            int nextPos = (historyPos + 1) % history.length;
            if (nextPos == historyCount) {
              historyPos = nextPos;
              commandField.setText("");
            } else if (historyPos == historyCount || history[nextPos] == null) {
              commandField.getToolkit().beep();
            } else {
              String cmd = trim(commandField.getText());
              if (cmd != null) {
                history[historyPos] = cmd;
              }
              historyPos = nextPos;
              commandField.setText(history[historyPos]);
            }
          }
        }
      }

    });
    cliResponseAggregator.start();

    panel.add(commandField, BorderLayout.SOUTH);
    setSize(500,500);
  }

  @Override
  public void closePlugin() {
    cliResponseAggregator.stop();
  }

  private static String trim(String text) {
    if (text != null) {
      text = text.trim();
      if (!text.isEmpty()) {
        return text;
      }
    }
    return null;
  }

  @Override
  public Mote getMote() {
    return mspMote;
  }

  @Override
  public String getQuickHelp() {
    return
        "<b>MSPSim's Command Line Interface</b>" +
        "<br><br>help<br><i>lists available commands</i>" +
        "<br><br>info CC2420<br><i>shows radio chip details</i>" +
        "<br><br>log CC2420 > mylog.txt<br><i>logs radio chip details to file</i>" +
        "<br><br>stacktrace<br><i>shows current stacktrace</i>";
  }

}
