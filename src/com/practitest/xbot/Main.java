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
import java.io.*;
import java.net.*;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author stask.
 *         <p/>
 *         TODO: upload task console output (merged stderr and stdout)
 */
public class Main {
    private static final Logger logger = Logger.getLogger(Main.class.getName());

    private static final String VERSION = Main.class.getPackage().getImplementationVersion() != null ? Main.class.getPackage().getImplementationVersion() : "INTERNAL";

    private static final String NO_TRAY_ICON_PROPERTY_KEY = "com.practitest.xbot.no_tray_icon";
    private static final String LISTENING_PORT_PROPERTY_KEY = "com.practitest.xbot.listening_port";

    private static final String XBOT_TRAY_CAPTION = "PractiTest xBot";

    private static final int DEFAULT_LISTENING_PORT = 18080;
    private static final int TEST_RUNNER_DELAY = 60;
    private static final int TEST_RUNNER_INITIAL_DELAY = 3;
    private static final int MAX_TEST_RUNNER_LOG = 100;

    private static final Pattern PARAMETER_PARSER_PATTERN = Pattern.compile("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'");

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
    private final Deque<String> testRunnerLog = new LinkedList<String>();

    private String apiKey = "";
    private String apiSecretKey = "";
    private String serverURL = "";
    private String clientId = "";
    private String proxyHost = "";
    private int proxyPort = -1;
    private String proxyUser = "";
    private String proxyPassword = "";

    public Main(int listeningPort, boolean noTrayIcon) throws Exception {
        logger.info("Running v" + VERSION);
        if (listeningPort > 0) {
            loadSettings();
            this.listeningPort = listeningPort;
            lock = new ReentrantLock();
            exitCondition = lock.newCondition();
            initializeHTTPListener();
            addTestRunnerLog("Running version " + VERSION);
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
        } // else -- running from test
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
                serverURL = settings.getProperty("server_url", "");
                apiKey = settings.getProperty("api_key", "");
                apiSecretKey = settings.getProperty("api_secret_key", "");
                clientId = settings.getProperty("client_id", "");
                proxyHost = settings.getProperty("proxy_host", "");
                proxyPort = Integer.parseInt(settings.getProperty("proxy_port", "-1"));
                proxyUser = settings.getProperty("proxy_user", "");
                proxyPassword = settings.getProperty("proxy_password", "");
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
        settings.setProperty("proxy_host", proxyHost);
        settings.setProperty("proxy_port", String.valueOf(proxyPort));
        settings.setProperty("proxy_user", proxyUser);
        settings.setProperty("proxy_password", proxyPassword);
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
                    printPreferences(response.getWriter());
                    ((Request) request).setHandled(true);
                } else if (target.equals("/set_preferences")) {
                    serverURL = request.getParameter("server_url");
                    apiKey = request.getParameter("api_key");
                    apiSecretKey = request.getParameter("api_secret_key");
                    clientId = request.getParameter("client_id");
                    proxyHost = request.getParameter("proxy_host");
                    try {
                        proxyPort = Integer.parseInt(request.getParameter("proxy_port"));
                    } catch (NumberFormatException e) {
                        proxyPort = -1;
                    }
                    proxyUser = request.getParameter("proxy_user");
                    proxyPassword = request.getParameter("proxy_password");
                    saveSettings();
                    initializeClient();
                    if (request.getParameterMap().containsKey("validate")) {
                        response.setContentType("text/html");
                        response.setStatus(HttpServletResponse.SC_OK);
                        printConnectionStatus(response.getWriter(), theClient.get().validate());
                    } else {
                        response.sendRedirect("/preferences");
                    }
                    ((Request) request).setHandled(true);
                } else if (target.equals("/log")) {
                    response.setContentType("text/html");
                    response.setStatus(HttpServletResponse.SC_OK);
                    PrintWriter out = response.getWriter();
                    out.println("<html><head><meta http-equiv=\"refresh\" content=\"5\" /><title>PractiTest xBot log</title></head>");
                    out.println("<body><h1>PractiTest xBot v" + VERSION + " log</h1><div>");
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

            private void printConnectionStatus(PrintWriter out, boolean status) {
                out.println("<html>");
                out.println("<body>");
                out.append("<h3>Connection status: ").append(status ? "OK" : "FAILED").append("</h3>");
                out.println("<a href='/preferences'>Back to preferences</a>");
                out.println("</body>");
                out.println("</html>");
            }

            private void printPreferences(PrintWriter out) {
                out.println("<html>");
                printHeader(out, "PractiTest xBot preferences");
                out.println("<body>");
                out.println("<body>");
                out.println("<form method='POST' action='set_preferences'>");
                out.println("<table>");

                out.println("<caption>PractiTest xBot configuration</caption>");

                out.println("<tr>");
                out.println("<th><label for='server_url'>PractiTest URL:</label></th>");
                out.println("<td><input type='text' id='server_url' name='server_url' value='" + serverURL + "' /></td>");
                out.println("</tr>");

                out.println("<tr>");
                out.println("<th><label for='api_key'>API Key:</label></th>");
                out.println("<td><input type='text' id='api_key' name='api_key' value='" + apiKey + "' /></td>");
                out.println("</tr>");

                out.println("<tr>");
                out.println("<th><label for='api_secret_key'>API Secret Key:</label></th>");
                out.println("<td><input type='text' id='api_secret_key' name='api_secret_key' value='" + apiSecretKey + "' /></td>");
                out.println("</tr>");

                out.println("<tr>");
                out.println("<th><label for='client_id'>Client ID:</label></th>");
                out.println("<td><input type='text' id='client_id' name='client_id' value='" + clientId + "' /></td>");
                out.println("</tr>");

                out.println("<tr>");
                out.println("<th><label for='proxy_host'>Proxy Host:</label></th>");
                out.println("<td><input type='text' id='proxy_host' name='proxy_host' value='" + proxyHost + "' /></td>");
                out.println("</tr>");

                out.println("<tr>");
                out.println("<th><label for='proxy_port'>Proxy Port:</label></th>");
                out.println("<td><input type='text' id='proxy_port' name='proxy_port' value='" + proxyPort + "' /></td>");
                out.println("</tr>");

                out.println("<tr>");
                out.println("<th><label for='proxy_user'>Proxy User Name:</label></th>");
                out.println("<td><input type='text' id='proxy_user' name='proxy_user' value='" + proxyUser + "' /></td>");
                out.println("</tr>");

                out.println("<tr>");
                out.println("<th><label for='proxy_password'>Proxy Password:</label></th>");
                out.println("<td><input type='text' id='proxy_password' name='proxy_password' value='" + proxyPassword + "' /></td>");
                out.println("</tr>");

                out.println("<tr>");
                out.println("<td colspan='2' class='actions'>");
                out.println("<a href='/log'>View Log</a>&nbsp;&nbsp;");
                //out.println("<input type='submit' name='validate' value='Validate &rArr;' />");
                out.println("<input type='submit' name='update' value='Update &rArr;' />");
                out.println("</td>");
                out.println("</tr>");

                out.println("</table>");
                out.println("</form>");
                out.println("</body>");
                out.println("</html>");
            }

            private void printHeader(PrintWriter out, String title) {
                out.append("<head>");
                out.append("<title>").append(title).append("</title>");
                out.append("<style>");
                out.append("table {width: 80%;}");
                out.append("th {text-align: right; width: 30%;}");
                out.append("td {text-align: left; width: 70%;}");
                out.append("td.actions {text-align: center; width: 100%;}");
                out.append("</style>");
                out.append("</head>");
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
        if (!proxyHost.isEmpty()) {
            System.setProperty("http.proxyHost", proxyHost);
            System.setProperty("https.proxyHost", proxyHost);
        }
        if (proxyPort > 0) {
            System.setProperty("http.proxyPort", String.valueOf(proxyPort));
            System.setProperty("https.proxyPort", String.valueOf(proxyPort));
        }
        if (!proxyUser.isEmpty()) {
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(proxyUser, proxyPassword.toCharArray());
                }
            });
            System.setProperty("http.proxyUser", proxyUser);
            System.setProperty("https.proxyPassword", proxyPassword);
            System.setProperty("https.proxyUser", proxyUser);
            System.setProperty("https.proxyPassword", proxyPassword);
        }
        theClient.set(new Client(serverURL, apiKey, apiSecretKey, clientId, VERSION));
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
                if (client != null)
                    runScript(client);
                else { // client is null
                    logger.warning("PractiTest client is not yet configured");
                    addTestRunnerLog("PractiTest client is not yet configured");
                }
                logger.info("TestRunner finished, going to sleep.");
                addTestRunnerLog("TestRunner finished, going to sleep.");
            }
        }, TEST_RUNNER_INITIAL_DELAY, TEST_RUNNER_DELAY, TimeUnit.SECONDS);
    }

    private void runScript(Client client) {
        try {
            Client.Task task = client.nextTask();
            if (task == null) {
                addTestRunnerLog("There is no test to run in the queue");
                trayIcon.setImage(trayIconImageReady);
                return;
            }
            String taskName = task.getDescription() + " [" + task.getPathToTestApplication() + "]";
            addTestRunnerLog("Running " + taskName);
            trayIcon.setImage(trayIconImageRunning);
            trayIcon.displayMessage(XBOT_TRAY_CAPTION, "PractiTest xBot is running: " + taskName, TrayIcon.MessageType.INFO);

            TaskRunner taskRunner = new TaskRunner(task);
            Thread taskRunnerThread = new Thread(taskRunner);
            taskRunnerThread.setDaemon(true);
            taskRunnerThread.start();
            taskRunnerThread.join();
            if (taskRunner.isTimedOut())
                addTestRunnerLog("Task [" + taskName + "] timed out");
            else
                addTestRunnerLog("Task [" + taskName + "] finished with exit code " + taskRunner.getExitCode());
            addTestRunnerLog("Task [" + taskName + "] output: [" + taskRunner.getOutput() + "]");
            addTestRunnerLog("Uploading test results..." +
                    (taskRunner.getResultFiles() == null ?
                            "[no result files]" :
                            taskRunner.getResultFiles().toString()));
            String uploadedTo = client.uploadResult(
                    new Client.TaskResult(
                            task.getInstanceId(),
                            taskRunner.getExitCode(),
                            taskRunner.getResultFiles(),
                            taskRunner.getOutput()));
            addTestRunnerLog("Finished uploading test results [" + uploadedTo + "].");
            trayIcon.setImage(trayIconImageReady);
            trayIcon.displayMessage(XBOT_TRAY_CAPTION, "PractiTest xBot finished running task, ready for the next one", TrayIcon.MessageType.INFO);
        } catch (IOException e) {
            errorDisplay(e.getMessage(), null);
        } catch (NoSuchAlgorithmException e) {
            errorDisplay(e.getMessage(), null);
        } catch (ParserConfigurationException e) {
            errorDisplay(e.getMessage(), null);
        } catch (SAXException e) {
            errorDisplay(e.getMessage(), null);
        } catch (Client.APIException e) {
            errorDisplay(e.getMessage(), "APIException: ");
        } catch (Throwable e) {
            errorDisplay(e.getMessage(), "Unhandled exception: ");
        }
    }

    private void errorDisplay(String message, String error_prefix) {
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

    /**
     * This class runs external process with given timeout.
     * The code is based on this article: http://kylecartmell.com/?p=9
     */
    class TaskRunner implements Runnable {
        private Client.Task task;

        private boolean timedOut = false;
        private int exitCode = -1;
        private java.util.List<File> resultFiles;
        private String output;

        public TaskRunner(Client.Task task) {
            this.task = task;
        }

        public boolean isTimedOut() {
            return timedOut;
        }

        public int getExitCode() {
            return exitCode;
        }

        public List<File> getResultFiles() {
            return resultFiles;
        }

        public String getOutput() {
            return output;
        }

        public void run() {
            Timer timer = null;
            Process process = null;
            boolean captureFiles = false;
            try {
                // parse the command line
                List<String> parameters = new ArrayList<String>();
                Matcher parametersMatcher = PARAMETER_PARSER_PATTERN.matcher(task.getPathToTestApplication());
                while (parametersMatcher.find()) {
                    if (parametersMatcher.group(1) != null) {
                        parameters.add(parametersMatcher.group(1));
                    } else if (parametersMatcher.group(2) != null) {
                        parameters.add(parametersMatcher.group(2));
                    } else {
                        parameters.add(parametersMatcher.group());
                    }
                }
                logger.info("Running command [" + parameters.toString() + "]");
                addTestRunnerLog("Running command [" + parameters.toString() + "]");
                File workingDirectory = new File(parameters.get(0)).getParentFile();
                ProcessBuilder processBuilder = new ProcessBuilder(parameters);
                processBuilder.directory(workingDirectory);
                processBuilder.redirectErrorStream(true);
                timer = new Timer(true);
                Interrupter interrupter = new Interrupter(Thread.currentThread());
                timer.schedule(interrupter, task.getTimeoutInSeconds() * 1000);
                process = processBuilder.start();
                StreamDrainer streamDrainer = new StreamDrainer(process.getInputStream());
                Thread streamDrainerThread = new Thread(streamDrainer);
                streamDrainerThread.setDaemon(true);
                streamDrainerThread.start();
                exitCode = process.waitFor();
                output = streamDrainer.getOutput();
                captureFiles = true;
            } catch (InterruptedException e) {
                // timeout expired
                addTestRunnerLog("Timeout expired for [" + task.getDescription() + "]");
                logger.warning("Timeout expired for [" + task.getDescription() + "]");
                timedOut = true;
                if (process != null)
                    process.destroy();
            } catch (IOException e) {
                // some other error
                addTestRunnerLog("IO exception while running [" + task.getDescription() + "]: " + e.getMessage());
                logger.warning("IO exception while running [" + task.getDescription() + "]: " + e.getMessage());
                if (process != null)
                    process.destroy();
            } finally {
                // If the process returns within the timeout period, we have to stop the interrupter
                // so that it does not unexpectedly interrupt some other code later.
                if (timer != null) timer.cancel();

                // We need to clear the interrupt flag on the current thread just in case
                // interrupter executed after waitFor had already returned but before timer.cancel
                // took effect.
                //
                // Oh, and there's also Sun bug 6420270 to worry about here.
                Thread.interrupted();
            }

            if (captureFiles) {
                File taskResultFilesDir = new File(task.getPathToTestResults());
                if (taskResultFilesDir.isDirectory()) {
                    File[] files = taskResultFilesDir.listFiles(new FileFilter() {
                        public boolean accept(File file) {
                            return file.isFile();
                        }
                    });
                    Arrays.sort(files, new Comparator<File>() {
                        public int compare(File left, File right) {
                            return Long.valueOf(left.lastModified()).compareTo(right.lastModified());
                        }
                    });
                    resultFiles = files.length > task.getNumOfFilesToUpload() ?
                            Arrays.asList(files).subList(0, task.getNumOfFilesToUpload()) :
                            Arrays.asList(files);
                } else if (taskResultFilesDir.isFile()) {
                    resultFiles = Arrays.asList(taskResultFilesDir);
                }
            }
        }

        private class Interrupter extends TimerTask {
            private Thread thread;

            public Interrupter(Thread thread) {
                this.thread = thread;
            }

            @Override
            public void run() {
                logger.info("Interrupting...");
                addTestRunnerLog("Interrupting...");
                thread.interrupt();
            }
        }

        private class StreamDrainer implements Runnable {
            private final BufferedReader reader;
            private final StringBuffer outputBuffer = new StringBuffer(); // use StringBuffer instead of StringBuilder -- synchronization

            private StreamDrainer(InputStream inputStream) {
                this.reader = new BufferedReader(new InputStreamReader(inputStream));
            }

            public void run() {
                String line;
                try {
                    while (!Thread.interrupted() && (line = reader.readLine()) != null) {
                        outputBuffer.append(line).append('\n');
                    }
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Failed to read process console stream", e);
                }
            }

            public String getOutput() {
                return outputBuffer.toString();
            }
        }
    }
}
