package at.enactmentengine.serverless.main;

import at.enactmentengine.serverless.simulation.SimulationParameters;
import at.enactmentengine.serverless.simulation.metadata.MetadataStore;
import at.enactmentengine.serverless.utils.LoggerUtil;
import at.uibk.dps.cronjob.ManualUpdate;
import at.uibk.dps.databases.MongoDBAccess;
import at.uibk.dps.util.Type;
import ch.qos.logback.classic.Level;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringEscapeUtils;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Main class of enactment engine which specifies the input file and starts the workflow on the machine on which it gets
 * started.
 * <p>
 * based on @author markusmoosbrugger, jakobnoeckl extended by @author stefanpedratscher; extended again as a part of
 * the simulator by @author mikahautz
 */
public class Local {

    /**
     * Logger for the local execution.
     */
    static final Logger logger = LoggerFactory.getLogger(Local.class);

    /**
     * Indicates whether the connection to the DB should be closed at the end.
     */
    private static boolean close = true;

    /**
     * Starting point of the local execution.
     *
     * @param args workflow.yaml [input.json]
     */
    public static void main(String[] args) {
        // sets the logging level to INFO only
        ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(Level.INFO);
        /* Workflow executor */
        Executor executor = new Executor();
        Simulator simulator = new Simulator();

        /* Check for inputs and execute workflow */
        Map<String, Object> result = null;
        try {
            int length = args.length;
            List<String> parameterList = Arrays.asList(args);
            boolean simulate = parameterList.contains("--simulate");
            if (simulate) {
                length -= 1;
                SimulationParameters.IGNORE_FT = parameterList.contains("--ignore-FT") || parameterList.contains("--ignore-ft");
                if (SimulationParameters.IGNORE_FT) {
                    length -= 1;
                }

                SimulationParameters.NO_DISTRIBUTION = parameterList.contains("--no-distribution");
                if (SimulationParameters.NO_DISTRIBUTION) {
                    length -= 1;
                }

                MetadataStore.FORCE_DATABASE_PROVIDER = parameterList.contains("--db");
                if (MetadataStore.FORCE_DATABASE_PROVIDER) {
                    length -= 1;
                }
            }
            boolean export = parameterList.contains("--export");
            if (export) {
                length -= 1;
            }
            boolean update = parameterList.contains("--update");
            if (update) {
                length -= 1;
                logger.info("Updating database. This could take a moment...");
                ManualUpdate.main(null);
                logger.info("Updating complete!");
            }
            boolean hideCredentials = parameterList.contains("--hide-credentials");
            if (hideCredentials) {
                LoggerUtil.HIDE_CREDENTIALS = true;
                length -= 1;
            }

            String workflowContent = null;
            String workflowInput = null;
            if (length > 0) {
                workflowContent = FileUtils.readFileToString(new File(args[0]));
            }
            if (length > 1) {
                workflowInput = FileUtils.readFileToString(new File(args[1]));
            }

            /* Measure start time of the workflow execution */
            long start = System.currentTimeMillis();

            if (length > 1 && simulate) {
                MongoDBAccess.saveLogWorkflowStart(Type.SIM, workflowContent, workflowInput, start);
                result = simulator.simulateWorkflow(args[0], args[1], -1, start);
            } else if (length > 0 && simulate) {
                MongoDBAccess.saveLogWorkflowStart(Type.SIM, workflowContent, null, start);
                result = simulator.simulateWorkflow(args[0], null, -1, start);
            } else if (length > 1) {
                MongoDBAccess.saveLogWorkflowStart(Type.EXEC, workflowContent, workflowInput, start);
                result = executor.executeWorkflow(args[0], args[1], -1, start);
            } else if (length > 0) {
                MongoDBAccess.saveLogWorkflowStart(Type.EXEC, workflowContent, null, start);
                result = executor.executeWorkflow(args[0], null, -1, start);
            } else {
                logger.error("Usage: java -jar enactment-engine-all.jar path/to/workflow.yaml [path/to/input.json] [--simulate] [--ignore-FT] [--update] [--export] [--hide-credentials]");
            }
            if (!simulate) {
                logger.info("Result: {}", result);
            }
            if (export) {
                exportLogsToFile();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                MongoDBAccess.addAllEntries();
                if (close) {
                    MongoDBAccess.close();
                }
            } catch (IOException e) {
                logger.info("No mongoDatabase.properties file found. Logs will not be stored in a database.");
            }
        }
    }

    /**
     * Executes the main method and returns a list of all logs.
     *
     * @param args given arguments
     *
     * @return a list of all logs
     */
    public static List<Document> executeAndGetLogs(String[] args) {
        close = false;
        main(args);
        List<Document> logs = MongoDBAccess.getAllEntries();
        MongoDBAccess.close();
        return logs;
    }

    /**
     * Exports all log entries to a file called "output.csv".
     */
    private static void exportLogsToFile() {
        StringBuilder header = new StringBuilder();
        StringBuilder sb = new StringBuilder();
        PrintWriter writer = null;
        String filename = "output.csv";
        try {
            writer = new PrintWriter(new FileWriter(filename, true));
        } catch (IOException e) {
            e.printStackTrace();
        }
        // get all log entries
        List<Document> logs = MongoDBAccess.getAllEntries();

        int i = 0;
        for (Document d : logs) {
            // iterate over every field in the document
            for (Map.Entry<String, Object> entry : d.entrySet()) {
                String key = entry.getKey();
                // ignore unneeded fields for the user
                if (key.equals("workflow_id") || key.equals("done") || key.equals("_id")) {
                    continue;
                }
                // if it is the first iteration, build the header
                if (i == 0) {
                    header.append(key);
                    header.append(",");
                }
                // if the entry is a date, reformat and append
                if (entry.getValue() instanceof Date) {
                    Date date = (Date) entry.getValue();
                    sb.append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX").format(date));
                } else if (entry.getValue() != null) {
                    if (entry.getValue().toString().contains(",")) {
                        // if the entry is not null, append it to the string-builder
                        sb.append("\"" + StringEscapeUtils.escapeJava(entry.getValue().toString()).replace("\"", "\"\"") + "\"");
                    } else {
                        // if the entry is not null, append it to the string-builder
                        sb.append(StringEscapeUtils.escapeJava(entry.getValue().toString()));
                    }

                }
                sb.append(",");
            }
            // if it is the first iteration and the file is empty, write the header
            if (i == 0 && new File(filename).length() == 0) {
                header.setLength(header.length() - 1);
                header.append("\n");
                writer.write(header.toString());
            } else if (i == 0) {
                // if it is the first iteration and the file already contains some values, insert an empty line as divider
                writer.write("\n");
            }
            // remove the last char (= ",") and append a newline
            sb.setLength(sb.length() - 1);
            sb.append("\n");
            // write the values to the file
            writer.write(sb.toString());
            // reset the string-builder
            sb.setLength(0);
            i++;
        }
        writer.close();
    }
}
