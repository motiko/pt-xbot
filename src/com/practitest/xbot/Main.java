package com.practitest.xbot;

import com.practitest.api.Client;
import org.mortbay.jetty.Request;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.handler.AbstractHandler;
import org.xml.sax.SAXException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Comparator;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * @author stask.
 */
public class Main {
    private static final Logger logger = Logger.getLogger(Main.class.getName());

    private static final String NO_TRAY_ICON_PROPERTY_KEY = "com.practitest.xbot.no_tray_icon";
    private static final String LISTENING_PORT_PROPERTY_KEY = "com.practitest.xbot.listening_port";

    private static final String XBOT_TRAY_CAPTION = "PractiTest xBot";

    private static final int DEFAULT_LISTENING_PORT = 18080;
    private static final int TEST_RUNNER_DELAY = 60;
    private static final int TEST_RUNNER_INITIAL_DELAY = 3;
    private static final int MAX_TEST_RUNNER_LOG = 10;

    private Image trayIconImageReady;
    private Image trayIconImageRunning;
    private Image trayIconImageError;
    private TrayIcon trayIcon;

    private int listeningPort;
    private Lock lock;
    private Condition exitCondition;
    private Server theServer;
    private AtomicReference<Client> theClient = new AtomicReference<Client>();
    private ScheduledFuture<?> testRunner;
    private final Deque<String> testRunnerLog;

    private String apiKey = "";
    private String apiSecretKey = "";
    private String serverURL = "";
    private String clientId = "";

    public Main(int listeningPort, boolean noTrayIcon) throws Exception {
        loadSettings();

        testRunnerLog = new LinkedList<String>();
        this.listeningPort = listeningPort;
        lock = new ReentrantLock();
        exitCondition = lock.newCondition();

        initializeHTTPListener();
        addTestRunnerLog("Loading with API Key: " + apiKey + " and serverURL: " + serverURL);
        initializeClient();
        initializeScheduler();
        if (!noTrayIcon) {
            initializeTrayIcon();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                logger.info("In shutdown hook");
                lock.lock();
                try {
                    exitCondition.signal();
                } finally {
                    lock.unlock();
                }
            }
        }));
    }

    public void run() {
        // wait for exit signal
        lock.lock();
        try {
            exitCondition.await();
        } catch (InterruptedException ignored) {
        } finally {
            lock.unlock();
        }
        // stop and exit
        logger.info("Stopping the internal http server...");
        try {
            theServer.stop();
        } catch (Exception e) {
            logger.severe("Failed to stop internal http server: " + e.getMessage());
        }
        logger.info("Stopped internal http server.");
        testRunner.cancel(false);
        // wait for completion of current task
        try {
            testRunner.get();
        } catch (InterruptedException ignored) {
        } catch (CancellationException ignored) {
        } catch (ExecutionException e) {
            logger.severe("Failed to execute test: " + e.getMessage());
        }
        System.exit(0);
    }

    public static void main(String[] args) throws Exception {
        boolean noTrayIcon = Boolean.parseBoolean(System.getProperty(NO_TRAY_ICON_PROPERTY_KEY, Boolean.FALSE.toString()));
        int listeningPort = Integer.parseInt(System.getProperty(LISTENING_PORT_PROPERTY_KEY, String.valueOf(DEFAULT_LISTENING_PORT)));

        Main me = new Main(listeningPort, noTrayIcon);
        me.run();
    }

    private void loadSettings() {
        File settingsFile = new File(System.getProperty("user.dir"), "xbot.properties");
        if (settingsFile.exists()) {
            try {
                Properties settings = new Properties();
                settings.load(new FileReader(settingsFile));
                serverURL = settings.getProperty("server_url");
                apiKey = settings.getProperty("api_key");
                apiSecretKey = settings.getProperty("api_secret_key");
                clientId = settings.getProperty("client_id");
            } catch (IOException ignore) {
            }
        }
    }

    private void saveSettings() {
        // overwrites the xbot.properties!
        Properties settings = new Properties();
        settings.setProperty("server_url", serverURL);
        settings.setProperty("api_key", apiKey);
        settings.setProperty("api_secret_key", apiSecretKey);
        settings.setProperty("client_id", clientId);
        try {
            settings.store(new FileWriter(new File(System.getProperty("user.dir"), "xbot.properties")),
                           "Please do not change this file manually, it'll be re-written by the application anyway.");
        } catch (IOException e) {
            logger.severe("Failed to store application settings: " + e.getMessage());
        }
    }

    private void initializeHTTPListener() throws Exception {
        theServer = new Server(listeningPort);
        theServer.setHandler(new AbstractHandler() {
            public void handle(String target,
                               HttpServletRequest request,
                               HttpServletResponse response,
                               int dispatch) throws IOException, ServletException {
                if (target.equals("/status")) {
                    response.setContentType("text/plain");
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.getWriter().println("OK");
                    ((Request) request).setHandled(true);
                } else if (target.equals("/preferences")) {
                    response.setContentType("text/html");
                    response.setStatus(HttpServletResponse.SC_OK);
                    PrintWriter out = response.getWriter();
                    out.println("<html><head><title>PractiTest xBot preferences</title></head>");
                    out.println("<body><form method=\"POST\" action=\"/set_preferences\">");
                    out.println("<table style=\"width:80%;\">");
                    out.println("<caption>PractiTest xBot configuration</caption>");
                    out.println("<tr>");
                    out.println("<th style=\"text-align:right; width:30%;\"><label for=\"server_url\">PractiTest URL:</label></th>");
                    out.println("<td style=\"text-align:left; width:70%;\"><input type=\"text\" id=\"server_url\" name=\"server_url\" value=\"" + serverURL + "\" /></td>");
                    out.println("</tr>");
                    out.println("<tr>");
                    out.println("<th style=\"text-align:right; width:30%;\"><label for=\"api_key\">API Key:</label></th>");
                    out.println("<td style=\"text-align:left; width:70%;\"><input type=\"text\" id=\"api_key\" name=\"api_key\" value=\"" + apiKey + "\" /></td>");
                    out.println("</tr>");
                    out.println("<tr>");
                    out.println("<th style=\"text-align:right; width:30%;\"><label for=\"api_secret_key\">API Secret Key:</label></th>");
                    out.println("<td style=\"text-align:left; width:70%;\"><input type=\"text\" id=\"api_secret_key\" name=\"api_secret_key\" value=\"" + apiSecretKey + "\" /></td>");
                    out.println("</tr>");

                    out.println("<tr>");
                    out.println("<th style=\"text-align:right; width:30%;\"><label for=\"client_id\">Client ID:</label></th>");
                    out.println("<td style=\"text-align:left; width:70%;\"><input type=\"text\" id=\"client_id\" name=\"client_id\" value=\"" + clientId + "\" /></td>");
                    out.println("</tr>");
                    
                    out.println("<tr>  <td colspan=\"2\">");
                    out.println("<a href=\"/log\">View Log</a> &nbsp; &nbsp;");
                    out.println("<input type=\"submit\" value=\"Update &rArr;\" />");
                    out.println("</td>  </tr>");
                    out.println("</table>");
                    out.println("</form></body></html>");
                    ((Request) request).setHandled(true);
                } else if (target.equals("/set_preferences")) {
                    serverURL = request.getParameter("server_url");
                    apiKey = request.getParameter("api_key");
                    apiSecretKey = request.getParameter("api_secret_key");
                    clientId = request.getParameter("client_id");
                    saveSettings();
                    initializeClient();
                    response.sendRedirect("/preferences");
                    ((Request) request).setHandled(true);
                } else if (target.equals("/log")) {
                    response.setContentType("text/html");
                    response.setStatus(HttpServletResponse.SC_OK);
                    PrintWriter out = response.getWriter();
                    out.println("<html><head><meta http-equiv=\"refresh\" content=\"5\" /><title>PractiTest xBot log</title></head>");
                    out.println("<body><h1>PractiTest xBot log</h1><div>");
                    synchronized (testRunnerLog) {
                        for (String message : testRunnerLog) {
                            out.println("<p>");
                            out.println(message);
                            out.println("</p>");
                        }
                    }
                    out.println("</div></body></html>");
                    ((Request) request).setHandled(true);
                }
            }
        });
        theServer.start();
    }

    private void initializeTrayIcon() {
        if (SystemTray.isSupported()) {
            SystemTray tray = SystemTray.getSystemTray();

            Image trayIconImageNotConfigured = loadImage("images/trayNotConfigured.png");
            trayIconImageReady = loadImage("images/trayReady.png");
            trayIconImageRunning = loadImage("images/trayRunning.png");
            trayIconImageError = loadImage("images/trayError.png");

            PopupMenu popup = new PopupMenu();
            MenuItem preferencesItem = new MenuItem("Preferences");
            preferencesItem.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent event) {
                    try {
                        Desktop.getDesktop().browse(new URI("http://localhost:" + listeningPort + "/preferences"));
                    } catch (IOException e) {
                        logger.severe("Failed to open URL: " + e.getMessage());
                    } catch (URISyntaxException e) {
                        logger.severe("Failed to open URL: " + e.getMessage());
                    }
                }
            });
            popup.add(preferencesItem);
            MenuItem logItem = new MenuItem("Log");
            logItem.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent event) {
                    try {
                        Desktop.getDesktop().browse(new URI("http://localhost:" + listeningPort + "/log"));
                    } catch (IOException e) {
                        logger.severe("Failed to open URL: " + e.getMessage());
                    } catch (URISyntaxException e) {
                        logger.severe("Failed to open URL: " + e.getMessage());
                    }
                }
            });
            popup.add(logItem);
            popup.addSeparator();
            MenuItem exitItem = new MenuItem("Exit");
            exitItem.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent event) {
                    logger.info("Exiting...");
                    lock.lock();
                    try {
                        exitCondition.signal();
                    } catch (IllegalMonitorStateException ignore) {
                    } finally {
                        lock.unlock();
                    }
                }
            });
            popup.add(exitItem);

            trayIcon = new TrayIcon(theClient.get() != null ? trayIconImageReady : trayIconImageNotConfigured, XBOT_TRAY_CAPTION, popup);

            trayIcon.setImageAutoSize(true);
            trayIcon.setToolTip(XBOT_TRAY_CAPTION);

            try {
                tray.add(trayIcon);
            } catch (AWTException e) {
                logger.severe("TrayIcon could not be added: " + e.getMessage());
            }
        } else {
            logger.warning("System tray is not supported");
        }
    }

    private void initializeClient() {
        theClient.set(null);
        if (serverURL.isEmpty() || apiKey.isEmpty() || apiSecretKey.isEmpty() || clientId.isEmpty()) return;
        theClient.set(new Client(serverURL, apiKey, apiSecretKey, clientId));
        if (trayIcon != null) {
            trayIcon.setImage(trayIconImageReady);
            trayIcon.displayMessage(XBOT_TRAY_CAPTION, "PractiTest xBot is ready", TrayIcon.MessageType.INFO);
        }
    }

    private void initializeScheduler() {
        final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        testRunner = scheduler.scheduleWithFixedDelay(new Runnable() {
            public void run() {
                logger.info("TestRunner is awake");
                addTestRunnerLog("TestRunner is awake getting next test to run");
                Client client = theClient.get();
                if (client != null) {
                    try {
                        Client.Task task = client.nextTask();
                        if (task == null) {
                          addTestRunnerLog("There is no test to run in the queue");
                          trayIcon.setImage(trayIconImageReady);
                        } else {
                            String taskName = task.getDescription() + " [" + task.getPathToTestApplication() + "]";
                            addTestRunnerLog("Running " + taskName);
                            trayIcon.setImage(trayIconImageRunning);
                            trayIcon.displayMessage(XBOT_TRAY_CAPTION, "PractiTest xBot is running: " + taskName, TrayIcon.MessageType.INFO);
                            Process childProcess = Runtime.getRuntime().exec(task.getPathToTestApplication());
                            int exitCode = childProcess.waitFor();
                            addTestRunnerLog("Finished " + taskName + " with exit code " + exitCode + ", uploading test results...");
                            
                            java.util.List<File> taskResultFiles = null;
                            File taskResultFilesDir = new File(task.getPathToTestResults());
                            if (taskResultFilesDir.isDirectory()) {
                                File[] files = taskResultFilesDir.listFiles(new FileFilter() {
                                    public boolean accept(File file) { return file.isFile();} });
                                Arrays.sort(files, new Comparator<File>(){
                                    public int compare(File f1, File f2)
                                        { return Long.valueOf(f1.lastModified()).compareTo(f2.lastModified());} 
                                });
                                int num_of_files = task.getnumOfFilesToUpload();
                                taskResultFiles = (files.length > num_of_files) ? 
                                    Arrays.asList(files).subList(0, num_of_files): 
                                    Arrays.asList(files);
                            }
                            client.uploadResult(new Client.TaskResult(task.getInstanceId(), exitCode, taskResultFiles));
                            addTestRunnerLog("Finished uploading test results.");
                            trayIcon.setImage(trayIconImageReady);
                            trayIcon.displayMessage(XBOT_TRAY_CAPTION, "PractiTest xBot finished running task, ready for the next one", TrayIcon.MessageType.INFO);
                        }
                    } catch (IOException e) {
                        errorDisplay(e.getMessage(), null);
                    } catch (NoSuchAlgorithmException e) {
                        errorDisplay(e.getMessage(), null);
                    } catch (ParserConfigurationException e) {
                        errorDisplay(e.getMessage(), null);
                    } catch (SAXException e) {
                        errorDisplay(e.getMessage(), null);
                    } catch (InterruptedException e) {
                        errorDisplay(e.getMessage(), "Error occurred during execution of task: ");
                      } catch (Client.APIException e) {
                        errorDisplay(e.getMessage(), "APIException: ");
                    } catch (Throwable e) {
                        errorDisplay(e.getMessage(), "Unhandled exception: ");
                    }
                } else {
                    logger.warning("PractiTest client is not yet configured");
                    addTestRunnerLog("PractiTest client is not yet configured");
                }
                logger.info("TestRunner finished, going to sleep.");
                addTestRunnerLog("TestRunner finished, going to sleep.");
            }
        }, TEST_RUNNER_INITIAL_DELAY, TEST_RUNNER_DELAY, TimeUnit.SECONDS);
    }
    
    private void errorDisplay(String message, String error_prefix){
        trayIcon.setImage(trayIconImageError);
        trayIcon.displayMessage(XBOT_TRAY_CAPTION, "PractiTest xBot failed to run task: " + message, TrayIcon.MessageType.ERROR);
        // the default is the communication error
        if (error_prefix == null)
            error_prefix = "Error occurred during communication with PractiTest server: ";
        logger.severe(error_prefix + message);
        addTestRunnerLog(error_prefix + message);
    }

    private void addTestRunnerLog(String message) {
        synchronized (testRunnerLog) {
            StringBuilder sb = new StringBuilder();
            sb.append(DateFormat.getDateTimeInstance().format(new Date())).append(" :: ").append(message);
            testRunnerLog.addFirst(sb.toString());
            if (testRunnerLog.size() > MAX_TEST_RUNNER_LOG) {
                testRunnerLog.removeLast();
            }
        }
    }

    private Image loadImage(String path) {
        URL internalPath = getClass().getResource("/" + path);
        if (internalPath == null) {
            logger.warning("Failed to load resource [" + path + "], falling back to regular path");
            return Toolkit.getDefaultToolkit().getImage(path);
        }
        return Toolkit.getDefaultToolkit().getImage(internalPath);
    }
}
