/**
 * BrowserComponentManager.java
 * Copyright (C) 2009 TMate Software Ltd
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * For information on how to redistribute this software under
 * the terms of a license other than GNU General Public License
 * contact TMate Software at support@sqljet.com
 */
package org.tmatesoft.sqljet.browser.core;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import org.tmatesoft.sqljet.browser.DBBrowserUtil;
import org.tmatesoft.sqljet.browser.core.data.DataComponent;
import org.tmatesoft.sqljet.browser.core.schema.SchemaComponent;
import org.tmatesoft.sqljet.core.SqlJetException;
import org.tmatesoft.sqljet.core.SqlJetVersion;

/**
 * @author TMate Software Ltd.
 * @author Sergey Scherbina (sergey.scherbina@gmail.com)
 *
 */
public class BrowserComponentManager implements ChangeListener, IProgress {
    
    public static BrowserComponentManager create(JFrame owner) {
        return new BrowserComponentManager(owner);
    }

    private JPanel myPanel;
    private JTabbedPane myComponent;
    private IBrowserComponent mySchemaPage;
    private IBrowserComponent myDataPage;
    
    private Set<IBrowserComponent> myInactivatedPages = new HashSet<IBrowserComponent>();
    private File myDBFile;
    private JFrame myOwner;

    private ExecutorService myExecutor = Executors.newSingleThreadExecutor();
    private Collection<Future<?>> myScheduledTasks = new LinkedList<Future<?>>();
    private JProgressBar myProgress;
    
    private Set<ChangeListener> myListeners = new HashSet<ChangeListener>();
    
    private BrowserComponentManager(JFrame owner) {
        myOwner = owner;
    }
    
    public void addChangeListener(ChangeListener listener) {
        synchronized (myListeners) {
            myListeners.add(listener);
        }
    }

    public void removeChangeListener(ChangeListener listener) {
        synchronized (myListeners) {
            myListeners.remove(listener);
        }
    }
    
    protected void fireStateChanged() {
        ChangeListener[] listeners = null;
        synchronized (myListeners) {
            listeners = (ChangeListener[]) myListeners.toArray(new ChangeListener[myListeners.size()]);
        }
        ChangeEvent event = new ChangeEvent(this);
        for (ChangeListener l : listeners) {
            l.stateChanged(event);
        }
    }
    
    public File getDBFile() {
        return myDBFile;
    }
    
    public JComponent getComponent() {
        if (myPanel != null) {
            return myPanel;
        }
        myPanel = new JPanel(new BorderLayout());
        mySchemaPage = new SchemaComponent();
        myDataPage = new DataComponent(this);

        myComponent = new JTabbedPane();

        mySchemaPage.getComponent().putClientProperty(this, mySchemaPage);
        myDataPage.getComponent().putClientProperty(this, myDataPage);

        myComponent.insertTab(mySchemaPage.getTitle(), null, mySchemaPage.getComponent(), mySchemaPage.getTip(), 0);
        myComponent.insertTab(myDataPage.getTitle(), null, myDataPage.getComponent(), myDataPage.getTip(), 1);
        
        myInactivatedPages.add(mySchemaPage);
        myInactivatedPages.add(myDataPage);
    
        myComponent.addChangeListener(this);
        
        myPanel.add(myComponent, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        final JLabel status = new JLabel(
                "<html><body><p>Powered by SQLJet v"
                        + SqlJetVersion.getVersionString()
                        + "<br>&copy; 2009, TMate Software, <a href=\"http://sqljet.com/\">http://sqljet.com/</a></p></body></html>");
        status.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        status.addMouseListener(new MouseAdapter() {

            @Override
            public void mouseClicked(MouseEvent e) {
                DBBrowserUtil.browse("http://sqljet.com/");
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                Cursor hand = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
                status.setCursor(hand);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                Cursor normal = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR);
                status.setCursor(normal);
            }
            
        });
        bottomPanel.add(status, BorderLayout.WEST);
        myProgress = new JProgressBar();
        myProgress.setBorder(BorderFactory.createEmptyBorder(4, 16, 4, 4));
        myProgress.setStringPainted(true);
        bottomPanel.add(myProgress, BorderLayout.CENTER);
        myPanel.add(bottomPanel, BorderLayout.SOUTH);
        
        myProgress.setVisible(false);

        return myPanel;
    }
    
    public void open(File dbFile) {
        myDBFile = dbFile;
        if (dbFile == null) {
            myOwner.setTitle("SQLJet Database Browser");
        } else {
            myOwner.setTitle("SQLJet Database Browser :: " + dbFile.getName());
        }
        
        myInactivatedPages.add(mySchemaPage);
        myInactivatedPages.add(myDataPage);
        fireStateChanged();
        // open only one that is active.
        stateChanged(null);
    }
    
    public void schedule(final IBrowserRunnable runnable, boolean cancelAll) {
        // cancel running and pending.        
        for (Iterator<Future<?>> tasks = myScheduledTasks.iterator(); tasks.hasNext();) {
            Future<?> task = tasks.next();
            if (task.isDone()) {
                tasks.remove();
            } else if (cancelAll) {
                task.cancel(true);
            }
        }
        
        myScheduledTasks.add(myExecutor.submit(new Runnable() {
            public void run() {
                runnable.run(BrowserComponentManager.this);
            }            
        }));        
    }

    public void stateChanged(ChangeEvent e) {
        IBrowserComponent component = getActivePage();
        if (component != null && myInactivatedPages.remove(component)) {
            try {
                component.open(myDBFile);
            } catch (SqlJetException e1) {
                open(null);
                JOptionPane.showMessageDialog(getOwner(), e1.getMessage(), "Error Opening DB", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    private IBrowserComponent getActivePage() {
        JComponent activeComponent = (JComponent) myComponent.getSelectedComponent();
        if (activeComponent == null) {
            return null;
        }
        return (IBrowserComponent) activeComponent.getClientProperty(this);
    }

    public JFrame getOwner() {
        return myOwner;
    }

    public void start(final String name, final int total) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                myProgress.setToolTipText(name);
                myProgress.setMinimum(0);
                myProgress.setMaximum(total);
                myProgress.setValue(0);
                // TODO show only in a second!
                myProgress.setVisible(true);
            }
        });
    }

    public void current(final int current) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                myProgress.setValue(current);
            }
        });
    }

    public void finish() {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                myProgress.setVisible(false);
            }
        });
    }
}
