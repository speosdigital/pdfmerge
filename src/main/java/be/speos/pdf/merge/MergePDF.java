package be.speos.pdf.merge;

// J2SE IO Packages
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;

// J2SE Utilities packages
import java.util.Date;
import java.util.Iterator;
import java.util.Properties;
import java.util.Vector;

// Log4J packages
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

// Itext PDF packages
import com.itextpdf.text.Document;
import com.itextpdf.text.pdf.PdfCopy;
import com.itextpdf.text.pdf.PdfImportedPage;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.PdfSmartCopy;
import com.itextpdf.text.pdf.PdfWriter;

// JSAP Command line parser package
import com.martiansoftware.jsap.FlaggedOption;
import com.martiansoftware.jsap.JSAP;
import com.martiansoftware.jsap.JSAPResult;
import com.martiansoftware.jsap.Switch;

// Speos Framework Exception package
import be.speos.framework.exceptions.ConfigurationException;
import be.speos.framework.exceptions.FrameworkExceptionLevel;

// Speos Framework Filesystem package
import be.speos.framework.commons.filesystem.FileSystem;

/**
 * Merge every PDF found in a directory into a single PDF file.
 * <p>
 * This class will a make a single PDF file containing every PDF found in
 * provided directory according to provided configuration. The provided
 * directory can be parsed recursively to add every PDF found in sub-directory.
 * </p>
 * <p>
 * <B>History</B>
 * <ul>
 * <li>20110303 - 0.0.1 : Original Release.</li>
 * <li>20120109 - 0.0.2 : Changes merging method to use iText PdfCopy or
 * PdfSmartCopy according to configuration.</li>
 * </ul>
 * </p>
 * </br>
 *
 * @version 0.0.2
 * @see com.itextpdf.text.pdf.PdfCopy
 * @see com.itextpdf.text.pdf.PdfSmartCopy
 */
public class MergePDF {

    /**
     * This is entry point of the merging application.
     * <p>
     * This method will look for PDFs in provided directory (recursively or not)
     * and add every page of these files into a single PDF file according to
     * common configuration. The default configuration will read from
     * 'GroupingPDF.properties' file. The configuration provided as command line
     * will override the default. This method will return true if merging happens
     * without a exception.
     * </p>
     *
     * @return return boolean based on merge success status
     * @param args Command line arguments.
     */
    public boolean merge(String[] args) {
        boolean mergeState = false;
        try {
            // Declarations
            Date processStartTime = null;
            Date processEndTime = null;
            long processTime = 0;
            long nbPDFsProcessed = 0;
            long nbPagesProcessed = 0;

            // Configure Log4J
            System.setProperty(SYSTEM_PROPERTY_DIRECTORY_HOME_KEY, FileSystem.getApplicationDirectory());
            PropertyConfigurator.configure(FileSystem.getProperties(FileSystem.getClassPathApplicationResourcePath(CONFIGURATION_FILE_LOG4J)));
            Logger.getLogger("be.speos").setLevel(Level.INFO);

            // Log the process start
            processStartTime = new Date();

            // Load configuration from file
            LOG.info("MergePDF started at " + dateFormat.format(processStartTime));
            LOG.info("Java Version : " + System.getProperty(SYSTEM_PROPERTY_JAVA_VERSION_KEY));
            LOG.info("Application directory : " + System.getProperty(SYSTEM_PROPERTY_DIRECTORY_HOME_KEY));

            // Load configuration
            LOG.info("Loading configuration...");
            loadConfiguration(args);
            LOG.info("Configuration loaded.");

            // Is debugging enabled ?
            if (commandLineArguments.getBoolean(CONFIG_KEY_LOG_DEBUG)) {
                // Update the logger level
                Logger.getLogger("be.speos").setLevel(Level.DEBUG);

                // Log the application parameters
                logApplicationParameters(args);
            }

            // Prepare the directories
            prepareDirectories();

            // Get the PDF files found in input directory that should be merged into a
            // single one
            LOG.debug("Retrieving every PDFs found in '" + inputDirectory + "'...");
            Vector<String> pdfsToGroup = FileSystem.getFiles(inputDirectory, CONFIG_FLAG_PDF_EXTENSION, commandLineArguments.getBoolean(CONFIG_KEY_INPUT_RECURSIVE_SEARCH));

            // If some PDF files were found
            if (!pdfsToGroup.isEmpty()) {
                // Get the first document to be merged
                String inputPdf = pdfsToGroup.elementAt(0);
                if (outputDirectoryIsInputDirectory && outputPdfFileNameBasedOnInput) {
                    // Rename current PDF
                    FileSystem.renameFile(inputPdf, inputPdf + ".old");
                    String newInputPdf = inputPdf + ".old";
                    pdfsToGroup.set(0, newInputPdf);
                }

                // Create handlers to manager merged PDF file according to configuration
                String mergedPdfFile = getOutputFilename(inputPdf);
                Document mergedPdfDocument = new Document();
                PdfWriter mergedPdfWriter = null;
                if (mergePdfOptimizingResourcesEnabled) {
                    mergedPdfWriter = new PdfSmartCopy(mergedPdfDocument, new FileOutputStream(mergedPdfFile));
                } else {
                    mergedPdfWriter = new PdfCopy(mergedPdfDocument, new FileOutputStream(mergedPdfFile));
                }
                mergedPdfDocument.open();

                // Create a new merge logging file
                String mergingLoggerFilePath = getLogFilename(mergedPdfFile);
                File mergingLoggerFile = new File(mergingLoggerFilePath);
                Writer mergingLoggerWriter = new BufferedWriter(new FileWriter(mergingLoggerFile));

                // For every PDF found in input
                LOG.info("Merging PDFs files...");
                for (String currentPdf : pdfsToGroup) {

                    // Add PDF the current group PDF
                    LOG.debug("Adding '" + currentPdf + "' to '" + mergedPdfFile + "'...");
                    int nbPagesMerged = mergePDF(currentPdf, mergedPdfWriter, mergedPdfDocument);
                    mergingLoggerWriter.write(currentPdf + "\t" + Integer.toString(nbPagesMerged) + "\r\n");
                    mergingLoggerWriter.flush();
                    LOG.debug("'" + currentPdf + "' added.");
                    nbPDFsProcessed++;
                    nbPagesProcessed += nbPagesMerged;

                    // Update progress display if asked
                    if (commandLineArguments.getBoolean(CONFIG_KEY_DISPLAY_PROGESS) && !commandLineArguments.getBoolean(CONFIG_KEY_LOG_DEBUG)) {
                        if (nbPDFsProcessed == 0) System.out.println("");
                        if ((nbPDFsProcessed % 5 == 0) && (nbPDFsProcessed % 10 != 0)) System.out.print("|");
                        if (nbPDFsProcessed % 10 == 0) System.out.print(nbPDFsProcessed);
                        else System.out.print(".");
                    }
                }

                // Close the merge logging file
                mergingLoggerWriter.close();

                // Close the current writer and document
                LOG.debug("Closing '" + mergedPdfFile + "'...");
                mergedPdfDocument.close();
                mergedPdfWriter.close();
                LOG.debug("'" + mergedPdfFile + "' closed.");

                if (commandLineArguments.getBoolean(CONFIG_KEY_DISPLAY_PROGESS) && !commandLineArguments.getBoolean(CONFIG_KEY_LOG_DEBUG))
                    System.out.println("");
                LOG.info(pdfsToGroup.size() + " PDF file(s) merged for a total of " + Long.toString(nbPagesProcessed) + " page(s).");
            } else {
                LOG.info("No PDF file found in '" + inputDirectory + "'.");
            }

            // Log the process time
            processEndTime = new Date();
            processTime = getElapsedTime(processStartTime, processEndTime);
            LOG.info("MergePDF ended at " + dateFormat.format(processEndTime));
            LOG.info("Process time : " + getFormattedElapsedTime(processTime) + " (" + processTime + " milliseconds.)");

            // End the application
            mergeState = true;
            return mergeState;
        } catch (Exception exception) {
            LOG.error("An error occured", exception);
            return mergeState;
        }
    }

    /**
     * Load the application configuration.
     * <p>
     * This method will set the application default configuration. It'll overwrite
     * the default with the values found in the configuration properties file and
     * finally check the command line arguments to replace the loaded
     * configuration.
     * </p>
     *
     * @param commandLineArgs The arguments list provided in command line when the
     *                        application was started.
     * @throws Exception A problem occurred while setting the application
     *                   configuration.
     */
    private static void loadConfiguration(final String[] commandLineArgs) throws Exception {
        // Initialize command line parameters
        String cmdLineArgs[] = new String[commandLineArgs.length];
        System.arraycopy(commandLineArgs, 0, cmdLineArgs, 0, cmdLineArgs.length);

        // If a configuration file was found
        if (FileSystem.isFileFoundInApplicationClasspath(CONFIGURATION_FILE)) {
            // Read the configuration file
            LOG.info("Properties file '" + CONFIGURATION_FILE + "' found. Reading default configuration...");
            config = FileSystem.getPropertiesFromApplicationClasspath(CONFIGURATION_FILE);
        }

        // No configuration file
        else {
            // Create a new set of properties
            config = new Properties();
            LOG.info("No properties file '" + CONFIGURATION_FILE + "' found. Internal default configuration used.");
        }

        // Create the command line arguments interpreter
        JSAP cmdLineInterpreter = new JSAP();

        // Add debug flag
        Switch swDebug = new Switch(CONFIG_KEY_LOG_DEBUG);
        swDebug.setShortFlag(JSAP.NO_SHORTFLAG);
        swDebug.setLongFlag("debug");
        swDebug.setHelp("Enable debug mode (By default OFF) \n(" + CONFIG_KEY_LOG_DEBUG + " key in properties file '" + CONFIGURATION_FILE + "' can also be used.)");
        swDebug.setDefault("false");
        if (config.containsKey(CONFIG_KEY_LOG_DEBUG)) {
            if (((String) config.get(CONFIG_KEY_LOG_DEBUG)).equalsIgnoreCase(CONFIG_FLAG_TRUE)) {
                swDebug.setDefault("true");
            }
        }
        cmdLineInterpreter.registerParameter(swDebug);

        // Add the display the process progressing
        Switch swForward = new Switch(CONFIG_KEY_DISPLAY_PROGESS);
        swForward.setShortFlag('f');
        swForward.setLongFlag("forward");
        swForward.setHelp("Display application progression on screen (By default OFF) \n(" + CONFIG_KEY_DISPLAY_PROGESS + " key in properties file '" + CONFIGURATION_FILE + "' can also be used.)");
        swForward.setDefault("false");
        if (config.containsKey(CONFIG_KEY_DISPLAY_PROGESS)) {
            if (((String) config.get(CONFIG_KEY_DISPLAY_PROGESS)).equalsIgnoreCase(CONFIG_FLAG_TRUE)) {
                swForward.setDefault("true");
            }
        }
        cmdLineInterpreter.registerParameter(swForward);

        // Add the recursive directory parsing flag
        Switch swDepth = new Switch(CONFIG_KEY_INPUT_RECURSIVE_SEARCH);
        swDepth.setShortFlag('d');
        swDepth.setLongFlag("depth");
        swDepth.setHelp("Directory structure will not be parsed recursively (By default ON) \n(" + CONFIG_KEY_INPUT_RECURSIVE_SEARCH + " key in properties file '" + CONFIGURATION_FILE + "' can also be used.)");
        swDepth.setDefault("true");
        if (config.containsKey(CONFIG_KEY_INPUT_RECURSIVE_SEARCH)) {
            if (((String) config.get(CONFIG_KEY_INPUT_RECURSIVE_SEARCH)).equalsIgnoreCase(CONFIG_FLAG_FALSE)) {
                swDepth.setDefault("false");
            }
        }
        cmdLineInterpreter.registerParameter(swDepth);

        // Add the PDF merging resources optimization flag
        Switch swPdfOptimzing = new Switch(CONFIG_KEY_MERGE_PDF_RESOURCES_OPTIMIZING);
        swPdfOptimzing.setShortFlag('z');
        swPdfOptimzing.setLongFlag("optimizeres");
        swPdfOptimzing.setHelp("Resources usage will be optimized during merge operation. Process time longer - Pdf smaller (By default OFF). \n(" + CONFIG_KEY_MERGE_PDF_RESOURCES_OPTIMIZING + " key in properties file '" + CONFIGURATION_FILE + "' can also be used.)");
        swPdfOptimzing.setDefault("false");
        if (config.containsKey(CONFIG_KEY_MERGE_PDF_RESOURCES_OPTIMIZING)) {
            if (((String) config.get(CONFIG_KEY_MERGE_PDF_RESOURCES_OPTIMIZING)).equalsIgnoreCase(CONFIG_FLAG_TRUE)) {
                swPdfOptimzing.setDefault("true");
            }
        }
        cmdLineInterpreter.registerParameter(swPdfOptimzing);

        // Add the input directory option
        FlaggedOption foDirIn = new FlaggedOption(CONFIG_KEY_INPUT_DIR);
        foDirIn.setShortFlag('i');
        foDirIn.setLongFlag("in");
        foDirIn.setUsageName("Input directory");
        foDirIn.setHelp("Input directory with pdf to be merged \n(" + CONFIG_KEY_INPUT_DIR + " key in properties file '" + CONFIGURATION_FILE + "' can also be used.)");
        foDirIn.setStringParser(JSAP.STRING_PARSER);
        foDirIn.setRequired(true);
        if (config.containsKey(CONFIG_KEY_INPUT_DIR)) {
            if (!((String) config.get(CONFIG_KEY_INPUT_DIR)).isEmpty()) {
                foDirIn.setDefault((String) config.get(CONFIG_KEY_INPUT_DIR));
                foDirIn.setRequired(false);
            }
        }
        cmdLineInterpreter.registerParameter(foDirIn);

        // Add the output directory option
        String defaultOutputDirectory = "";
        if (config.containsKey(CONFIG_KEY_OUTPUT_DIR)) {
            if (!((String) config.get(CONFIG_KEY_OUTPUT_DIR)).isEmpty()) {
                defaultOutputDirectory = (String) config.get(CONFIG_KEY_OUTPUT_DIR);
            }
        }
        FlaggedOption foDirOut = new FlaggedOption(CONFIG_KEY_OUTPUT_DIR);
        foDirOut.setShortFlag('o');
        foDirOut.setLongFlag("out");
        foDirOut.setUsageName("Output directory");
        foDirOut.setHelp("Output directory where signed pdf's will be written. Default is '" + defaultOutputDirectory + "'. \n(" + CONFIG_KEY_OUTPUT_DIR + " key in properties file '" + CONFIGURATION_FILE + "' can also be used.)");
        foDirOut.setStringParser(JSAP.STRING_PARSER);
        foDirOut.setRequired(false);
        if (!defaultOutputDirectory.isEmpty()) {
            foDirOut.setDefault(defaultOutputDirectory);
        }
        cmdLineInterpreter.registerParameter(foDirOut);

        // Add the log file option
        FlaggedOption foLogOut = new FlaggedOption(CONFIG_KEY_OUTPUT_LOG_NAME);
        foLogOut.setShortFlag('l');
        foLogOut.setLongFlag("log");
        foLogOut.setUsageName("Log file name");
        foLogOut.setHelp("Name of log file containg the list of PDFs that have been added into the generated PDF. \n(" + CONFIG_KEY_OUTPUT_LOG_NAME + " key in properties file '" + CONFIGURATION_FILE + "' can also be used.)");
        foLogOut.setStringParser(JSAP.STRING_PARSER);
        foLogOut.setRequired(false);
        foLogOut.setDefault(CONFIG_FLAG_OUTPUT_LOG_NAME);
        if (config.containsKey(CONFIG_KEY_OUTPUT_LOG_NAME)) {
            if (!((String) config.get(CONFIG_KEY_OUTPUT_LOG_NAME)).isEmpty()) {
                foLogOut.setDefault((String) config.get(CONFIG_KEY_OUTPUT_LOG_NAME));
            }
        }
        cmdLineInterpreter.registerParameter(foLogOut);

        // Add the output PDF file name option
        String defaultOutputPdfName = "";
        if (config.containsKey(CONFIG_KEY_OUTPUT_PDF_NAME)) {
            if (!((String) config.get(CONFIG_KEY_OUTPUT_PDF_NAME)).isEmpty()) {
                defaultOutputPdfName = (String) config.get(CONFIG_KEY_OUTPUT_PDF_NAME);
            }
        }
        FlaggedOption foNameOut = new FlaggedOption(CONFIG_KEY_OUTPUT_PDF_NAME);
        foNameOut.setShortFlag('n');
        foNameOut.setLongFlag("name");
        foNameOut.setUsageName("Output PDF Name");
        foNameOut.setHelp("Name of generated PDF file containing every PDF found in input directory. Default is '" + defaultOutputPdfName + "'. \n(" + CONFIG_KEY_OUTPUT_PDF_NAME + " key in properties file '" + CONFIGURATION_FILE + "' can also be used.)");
        foNameOut.setStringParser(JSAP.STRING_PARSER);
        foNameOut.setRequired(false);
        if (!defaultOutputPdfName.isEmpty()) {
            foNameOut.setDefault(defaultOutputPdfName);
        }
        cmdLineInterpreter.registerParameter(foNameOut);

        // Add the output ID file name option based on a split
        String defaultOutputPdfIDSplitRegex = "";
        if (config.containsKey(CONFIG_KEY_OUTPUT_PDF_ID_SPLIT_REGEX)) {
            if (!((String) config.get(CONFIG_KEY_OUTPUT_PDF_ID_SPLIT_REGEX)).isEmpty()) {
                defaultOutputPdfIDSplitRegex = (String) config.get(CONFIG_KEY_OUTPUT_PDF_ID_SPLIT_REGEX);
            }
        }
        FlaggedOption foIDRegexOut = new FlaggedOption(CONFIG_KEY_OUTPUT_PDF_ID_SPLIT_REGEX);
        foIDRegexOut.setShortFlag('r');
        foIDRegexOut.setLongFlag("splitregex");
        foIDRegexOut.setUsageName("Split regular expression");
        foIDRegexOut.setHelp("Regular expression used to split input PDF file name in order to generate output PDF file name. Default is '" + defaultOutputPdfIDSplitRegex + "'. \n(" + CONFIG_KEY_OUTPUT_PDF_ID_SPLIT_REGEX + " key in properties file '" + CONFIGURATION_FILE + "' can also be used.)");
        foIDRegexOut.setStringParser(JSAP.STRING_PARSER);
        foIDRegexOut.setRequired(false);
        if (!defaultOutputPdfIDSplitRegex.isEmpty()) {
            foIDRegexOut.setDefault(defaultOutputPdfIDSplitRegex);
        }
        cmdLineInterpreter.registerParameter(foIDRegexOut);
        int defaultOutputPdfIDSplitIndex = 0;
        if (config.containsKey(CONFIG_KEY_OUTPUT_PDF_ID_SPLIT_IDX)) {
            if (!((String) config.get(CONFIG_KEY_OUTPUT_PDF_ID_SPLIT_IDX)).isEmpty()) {
                defaultOutputPdfIDSplitIndex = Integer.parseInt((String) config.get(CONFIG_KEY_OUTPUT_PDF_ID_SPLIT_IDX));
            }
        }
        FlaggedOption foIDIndexOut = new FlaggedOption(CONFIG_KEY_OUTPUT_PDF_ID_SPLIT_IDX);
        foIDIndexOut.setShortFlag('p');
        foIDIndexOut.setLongFlag("splitpartpos");
        foIDIndexOut.setUsageName("Part position");
        foIDIndexOut.setHelp("Index to identify the part once input PDF file name has been splitted in order to generate output PDF file name. Default is '" + defaultOutputPdfIDSplitRegex + "'. \n(" + CONFIG_KEY_OUTPUT_PDF_ID_SPLIT_IDX + " key in properties file '" + CONFIGURATION_FILE + "' can also be used.)");
        foIDIndexOut.setStringParser(JSAP.INTEGER_PARSER);
        foIDIndexOut.setRequired(false);
        if (defaultOutputPdfIDSplitIndex > 0) {
            foIDIndexOut.setDefault(Integer.toString(defaultOutputPdfIDSplitIndex));
        }
        cmdLineInterpreter.registerParameter(foIDIndexOut);

        // Add the output ID file name option based on an extract
        int defaultOutputPdfIDExtractStart = 0;
        if (config.containsKey(CONFIG_KEY_OUTPUT_PDF_ID_EXTRACT_FROM)) {
            if (!((String) config.get(CONFIG_KEY_OUTPUT_PDF_ID_EXTRACT_FROM)).isEmpty()) {
                defaultOutputPdfIDExtractStart = Integer.parseInt((String) config.get(CONFIG_KEY_OUTPUT_PDF_ID_EXTRACT_FROM));
            }
        }
        FlaggedOption foFromExtractOut = new FlaggedOption(CONFIG_KEY_OUTPUT_PDF_ID_EXTRACT_FROM);
        foFromExtractOut.setShortFlag('b');
        foFromExtractOut.setLongFlag("extractbegin");
        foFromExtractOut.setUsageName("Index of first character");
        foFromExtractOut.setHelp("Index of first character of input file name where the output PDF file name will be extracted from. Default is '" + Integer.toString(defaultOutputPdfIDExtractStart) + "'. \n(" + CONFIG_KEY_OUTPUT_PDF_ID_EXTRACT_FROM + " key in properties file '" + CONFIGURATION_FILE + "' can also be used.)");
        foFromExtractOut.setStringParser(JSAP.INTEGER_PARSER);
        foFromExtractOut.setRequired(false);
        if (defaultOutputPdfIDExtractStart > 0) {
            foFromExtractOut.setDefault(Integer.toString(defaultOutputPdfIDExtractStart));
        }
        cmdLineInterpreter.registerParameter(foFromExtractOut);
        int defaultOutputPdfIDExtractLength = 0;
        if (config.containsKey(CONFIG_KEY_OUTPUT_PDF_ID_EXTRACT_LENGTH)) {
            if (!((String) config.get(CONFIG_KEY_OUTPUT_PDF_ID_EXTRACT_LENGTH)).isEmpty()) {
                defaultOutputPdfIDExtractLength = Integer.parseInt((String) config.get(CONFIG_KEY_OUTPUT_PDF_ID_EXTRACT_LENGTH));
            }
        }
        FlaggedOption foFromExtractLen = new FlaggedOption(CONFIG_KEY_OUTPUT_PDF_ID_EXTRACT_LENGTH);
        foFromExtractLen.setShortFlag('s');
        foFromExtractLen.setLongFlag("extractsize");
        foFromExtractLen.setUsageName("Number of characters to extract");
        foFromExtractLen.setHelp("Number of characters that should be extracted from input file name in order to build the output PDF file name. Default is '" + defaultOutputPdfIDSplitRegex + "'. \n(" + CONFIG_KEY_OUTPUT_PDF_ID_EXTRACT_LENGTH + " key in properties file '" + CONFIGURATION_FILE + "' can also be used.)");
        foFromExtractLen.setStringParser(JSAP.INTEGER_PARSER);
        foFromExtractLen.setRequired(false);
        if (defaultOutputPdfIDExtractLength > 0) {
            foFromExtractLen.setDefault(Integer.toString(defaultOutputPdfIDExtractLength));
        }
        cmdLineInterpreter.registerParameter(foFromExtractLen);

        // Parse the arguments
        commandLineArguments = cmdLineInterpreter.parse(cmdLineArgs);

        // If the awaited configuration is not correct
        if (!commandLineArguments.success()) {
            // Create a buffer for error message
            StringBuffer errorMessageBuffer = new StringBuffer();

            // Save specific error messages describing the problems
            errorMessageBuffer.append("Error in configuration : command line arguments\n-------------------------------------------\n");
            for (Iterator<?> errors = commandLineArguments.getErrorMessageIterator(); errors.hasNext(); ) {
                errorMessageBuffer = errorMessageBuffer.append("Error: " + errors.next() + "\n");
            }
            errorMessageBuffer = errorMessageBuffer.append("\n");

            // Add the default configuration loaded from properties file
            if (config != null) {
                if (!config.isEmpty()) {
                    // Log the default configuration read from properties files
                    errorMessageBuffer = errorMessageBuffer.append("Default configuration loaded from " + CONFIGURATION_FILE + ": \n");
                    for (Object configurationKey : config.keySet()) {
                        errorMessageBuffer = errorMessageBuffer.append("\t" + (String) configurationKey + "=" + config.getProperty((String) configurationKey) + "\n");
                    }
                }
            }
            errorMessageBuffer = errorMessageBuffer.append("\n");

            // Print the usage
            errorMessageBuffer = errorMessageBuffer.append("Command line arguments Usage\n-----------------------------\n");
            if (FileSystem.applicationIsExcutedFromJar()) {
                errorMessageBuffer = errorMessageBuffer.append("java -jar MergePdf.jar                " + cmdLineInterpreter.getUsage() + "\n");
            } else {
                errorMessageBuffer = errorMessageBuffer.append("java -classpath " + System.getProperty("java.class.path") + "' " + MergePDF.class.getName() + "\n                " + cmdLineInterpreter.getUsage() + "\n");
            }

            // Print a help
            errorMessageBuffer = errorMessageBuffer.append("Command line arguments help\n---------------------------\n\n");
            errorMessageBuffer = errorMessageBuffer.append(cmdLineInterpreter.getHelp()).append("\n");

            // Log the error$
            String errorMessage = errorMessageBuffer.toString();
            LOG.error(errorMessage);
            System.err.println(errorMessage);
            System.exit(1);
        }

        // Initialize the process of building output file name according by
        // configuration
        outputPdfFileNameBasedOnInput = true;
        outputPdfFileNameBasedOnIDExtract = false;
        outputPdfFileNameBasedOnIDSplit = false;

        // If the log file name has been provided
        if (commandLineArguments.contains(CONFIG_KEY_OUTPUT_LOG_NAME)) {
            // Get the log file name
            outputLogFileName = commandLineArguments.getString(CONFIG_KEY_OUTPUT_LOG_NAME);
            if (outputLogFileName != null && !outputLogFileName.isEmpty()) {
                // If it does not end with correct extension
                if (!outputLogFileName.toLowerCase().endsWith(CONFIG_FLAG_LOG_EXTENSION)) {
                    // Add extension
                    outputLogFileName = outputLogFileName + CONFIG_FLAG_LOG_EXTENSION;
                }
            }
        }

        // If the output file name has been provided
        if (commandLineArguments.contains(CONFIG_KEY_OUTPUT_PDF_NAME)) {
            // Initialize the output filenames (PDF & LOG)
            outputPdfFileName = commandLineArguments.getString(CONFIG_KEY_OUTPUT_PDF_NAME);

            // Disable name extraction using input file name
            outputPdfFileNameBasedOnInput = false;
            outputPdfFileNameBasedOnIDExtract = false;
            outputPdfFileNameBasedOnIDSplit = false;
        }

        // If an extract of input file name has been asked in order to build file
        // name
        else if (commandLineArguments.contains(CONFIG_KEY_OUTPUT_PDF_ID_EXTRACT_FROM)) {
            // Retrieve the value to use
            outputPdfFileNameBasedOnIDExtractFrom = commandLineArguments.getInt(CONFIG_KEY_OUTPUT_PDF_ID_EXTRACT_FROM);
            outputPdfFileNameBasedOnIDExtractLength = commandLineArguments.getInt(CONFIG_KEY_OUTPUT_PDF_ID_EXTRACT_LENGTH);

            // Enable name extraction using input file name
            outputPdfFileNameBasedOnInput = false;
            outputPdfFileNameBasedOnIDExtract = true;
            outputPdfFileNameBasedOnIDSplit = false;

            // Validate configuration
            if (outputPdfFileNameBasedOnIDExtractFrom <= 0)
                throw new ConfigurationException("Name for generated PDF should extracted from input file name but the provided start character is invalid. It must be > 0. Provided value through command line or property '" + CONFIG_KEY_OUTPUT_PDF_ID_EXTRACT_FROM + "' within configuration file '" + CONFIGURATION_FILE + "' : '" + Integer.toString(outputPdfFileNameBasedOnIDExtractFrom) + "'.", FrameworkExceptionLevel.FATAL);
            if (outputPdfFileNameBasedOnIDExtractLength <= 0)
                throw new ConfigurationException("Name for generated PDF should extracted from input file name but the provided length is invalid. It must be > 0. Provided value through command line or property '" + CONFIG_KEY_OUTPUT_PDF_ID_EXTRACT_LENGTH + "' within configuration file '" + CONFIGURATION_FILE + "' : '" + Integer.toString(outputPdfFileNameBasedOnIDExtractLength) + "'.", FrameworkExceptionLevel.FATAL);
        }

        // If a split of input file name has been asked in order to build file
        // name
        else if (commandLineArguments.contains(CONFIG_KEY_OUTPUT_PDF_ID_SPLIT_REGEX)) {
            // Retrieve the value to use
            outputPdfFileNameBasedOnIDSplitRegex = commandLineArguments.getString(CONFIG_KEY_OUTPUT_PDF_ID_SPLIT_REGEX);
            outputPdfFileNameBasedOnIDSplitIndex = commandLineArguments.getInt(CONFIG_KEY_OUTPUT_PDF_ID_SPLIT_IDX);

            // Enable name extraction using input file name
            outputPdfFileNameBasedOnInput = false;
            outputPdfFileNameBasedOnIDExtract = false;
            outputPdfFileNameBasedOnIDSplit = true;

            // Validate configuration
            if (outputPdfFileNameBasedOnIDSplitRegex == null || outputPdfFileNameBasedOnIDSplitRegex.isEmpty())
                throw new ConfigurationException("Name for generated PDF should extracted from input file name using a regular expression but no expression provided through command line or property '" + CONFIG_KEY_OUTPUT_PDF_ID_SPLIT_REGEX + "' within configuration file '" + CONFIGURATION_FILE + "' : '" + outputPdfFileNameBasedOnIDSplitRegex + "'.", FrameworkExceptionLevel.FATAL);
            if (outputPdfFileNameBasedOnIDSplitIndex <= 0)
                throw new ConfigurationException("Name for generated PDF should extracted from input file name using a regular expression but the provided index is invalid. It must be > 0. Provided value through command line or property '" + CONFIG_KEY_OUTPUT_PDF_ID_SPLIT_IDX + "' within configuration file '" + CONFIGURATION_FILE + "' : '" + Integer.toString(outputPdfFileNameBasedOnIDSplitIndex) + "'.", FrameworkExceptionLevel.FATAL);
        }

        // Update optimizing flag according to configuration or command line
        // parameters
        if (commandLineArguments.contains(CONFIG_KEY_MERGE_PDF_RESOURCES_OPTIMIZING)) {
            // Set the flag according to configuration
            mergePdfOptimizingResourcesEnabled = commandLineArguments.getBoolean(CONFIG_KEY_MERGE_PDF_RESOURCES_OPTIMIZING);
        }
    }

    /**
     * Prepare directories for process.
     * <p>
     * The preparation of directories consists of checking the input and output
     * directory existence. The output directory will be created if it is not
     * found.
     * </p>
     *
     * @throws Exception Something went wrong will preparing directories
     */
    private static void prepareDirectories() throws Exception {
        // Initialize
        String providedDirectory = null;

        // Prepare the input directory path
        providedDirectory = commandLineArguments.getString(CONFIG_KEY_INPUT_DIR);
        providedDirectory = FileSystem.qualifyPath(providedDirectory);
        if (!FileSystem.isStartingWithRoot(providedDirectory)) {
            providedDirectory = FileSystem.getApplicationDirectory() + providedDirectory;
        }
        inputDirectory = FileSystem.qualifyPath(providedDirectory);
        LOG.debug("Input directory = '" + inputDirectory + "'");

        // Prepare the output directory path
        providedDirectory = commandLineArguments.getString(CONFIG_KEY_OUTPUT_DIR);
        if (providedDirectory != null && !providedDirectory.isEmpty()) {
            providedDirectory = FileSystem.qualifyPath(providedDirectory);
            if (!FileSystem.isStartingWithRoot(providedDirectory)) {
                providedDirectory = FileSystem.getApplicationDirectory() + providedDirectory;
            }
        } else {
            providedDirectory = inputDirectory;
        }
        outputDirectory = FileSystem.qualifyPath(providedDirectory);
        LOG.debug("Output directory = '" + outputDirectory + "'");

        // Check if the input directory exists
        LOG.debug("Check input directory : '" + inputDirectory + "'");
        if (!FileSystem.isDirectory(inputDirectory)) {
            LOG.debug("Input directory : '" + inputDirectory + "' not found or not a directory.");
            throw new FileNotFoundException("The input directory '" + inputDirectory + "' was not found or is not a directory.");
        }

        // Check if the output directory exists
        LOG.debug("Check output directory : '" + outputDirectory + "'");
        if (!FileSystem.exists(outputDirectory)) {
            LOG.debug("Output directory : '" + outputDirectory + "' not found.");
            FileSystem.createDir(outputDirectory);
            LOG.debug("Output directory : '" + outputDirectory + "' created.");
        } else if (!FileSystem.isDirectory(outputDirectory)) {
            LOG.debug("Output directory : '" + outputDirectory + "' already exists and is not a directory.");
            throw new FileNotFoundException("The output directory '" + outputDirectory + "' was found BUT is NOT a directory.");
        }

        // Check if we have to duplicate input
        outputDirectoryIsInputDirectory = (inputDirectory.equalsIgnoreCase(outputDirectory));
    }

    /**
     * Merge provided PDF.
     * <p>
     * This method will add provided PDF file to the provided PDF document through
     * the provided PDF document writer. The number of pages added to the writer
     * will be returned.
     * </p>
     *
     * @param pdfToMerge      The path to the PDF that should be added to existing
     *                        document.
     * @param mergedPdfWriter The DocWriter for merged PDF file
     * @param mergedDocument  The generic Document for merged PDF.
     * @return The number of pages added to the writer.
     * @throws Exception Something went wrong while adding provided PDF to merged
     *                   PDF file through provided writer.
     */
    private static int mergePDF(final String pdfToMerge, PdfWriter mergedPdfWriter, Document mergedDocument) throws Exception {
        // Create a new reader for current PDF
        PdfReader reader = new PdfReader(pdfToMerge);
        PdfImportedPage page;

        // Add every page to provided writer
        int pageIndexInCurrentPdf = 1;
        while (pageIndexInCurrentPdf <= reader.getNumberOfPages()) {
            // Get the page from reader
            page = mergedPdfWriter.getImportedPage(reader, pageIndexInCurrentPdf);

            // Add page to merged document according using PDF copy
            ((PdfCopy) mergedPdfWriter).addPage(page);

            // Go to next page
            pageIndexInCurrentPdf++;
        }

        // Flush PDF current content
        mergedPdfWriter.flush();
        mergedPdfWriter.freeReader(reader);

        // Close the reader
        reader.close();
        page = null;

        // Return the number of pages imported
        return (pageIndexInCurrentPdf - 1);
    }

    /**
     * Retrieve the output filename.
     * <p>
     * This method will build and return the fully qualified filename that should
     * be given to the file once it has been processed.
     * </p>
     *
     * @param inputFilePath The fully qualified filename of the input file.
     * @return The name that should be provided to the file once it has been
     *         processed.
     * @throws Exception Something went wrong while building the merged PDF file
     *           name.
     */
    private static String getOutputFilename(final String inputFilePath) throws Exception {
        // Extract the input filename
        LOG.info("Building merged PDF file name...");
        StringBuffer inputfileName = new StringBuffer(FileSystem.getFilename(inputFilePath));

        // If merged PDF file name was not provided through configuration
        if (outputPdfFileName == null || !outputPdfFileName.isEmpty()) {
            // If the input file name should be used for merged PDF file
            if (outputPdfFileNameBasedOnInput) {
                outputPdfFileName = inputfileName.toString();
            }
            // If the an extract of input file name should be used for merged PDF file
            else if (outputPdfFileNameBasedOnIDExtract) {
                // Check if provided index is valid
                if (outputPdfFileNameBasedOnIDExtractFrom < 1 || outputPdfFileNameBasedOnIDExtractFrom > inputfileName.length()) {
                    throw new ConfigurationException("Merged PDF file name should be build with an extract of input file name that starts at character '" + Integer.toString(outputPdfFileNameBasedOnIDExtractFrom) + "'. This index is outside the limit of PDF file name '" + Integer.toString(inputfileName.length()) + "'.", FrameworkExceptionLevel.FATAL);
                }

                // Extract the desired part of the file name
                outputPdfFileName = inputfileName.substring(outputPdfFileNameBasedOnIDExtractFrom - 1, outputPdfFileNameBasedOnIDExtractFrom + outputPdfFileNameBasedOnIDExtractLength - 1);
            }
            // If the a part of input file name should be used for merged PDF file
            else if (outputPdfFileNameBasedOnIDSplit) {
                // Split the input filename
                String[] splittedFilename = inputfileName.toString().split(outputPdfFileNameBasedOnIDSplitRegex);

                // Check if provided index is valid
                if (outputPdfFileNameBasedOnIDSplitIndex < 1 || outputPdfFileNameBasedOnIDSplitIndex > splittedFilename.length) {
                    throw new ConfigurationException("Merged PDF file name should be build according to a split based on regular expression '" + outputPdfFileNameBasedOnIDSplitRegex + "' and by extracting part found at index '" + Integer.toString(outputPdfFileNameBasedOnIDSplitIndex) + "'. This index is out of bounds : 1 -> " + Integer.toString(splittedFilename.length) + ".", FrameworkExceptionLevel.FATAL);
                }

                // Build the filename using the part found at provided index
                outputPdfFileName = splittedFilename[outputPdfFileNameBasedOnIDSplitIndex - 1];
            }
        }

        // If it does not end with correct extension
        if (!outputPdfFileName.toLowerCase().endsWith(CONFIG_FLAG_PDF_EXTENSION)) {
            // Add extension and build log file name
            outputPdfFileName = outputPdfFileName + CONFIG_FLAG_PDF_EXTENSION;
        }

        // Build the merged PDF file name
        StringBuffer outputFilename = new StringBuffer(outputDirectory).append(outputPdfFileName);

        // Return the created file name
        LOG.info("Merged PDF file name '" + outputFilename.toString() + "' built.");
        return outputFilename.toString();
    }

    /**
     * Retrieve the log filename.
     * <p>
     * This method will build and return the fully qualified filename that should
     * be given to the log file.
     * </p>
     *
     * @param mergedPdfFilePath The fully qualified filename of the generated
     *                          file.
     * @return The name that should be provided to the log file.
     * @throws Exception Something went wrong while building the log file name.
     */
    private static String getLogFilename(final String mergedPdfFilePath) throws Exception {
        // Initialize
        LOG.info("Building log file name...");
        String logFileName = null;

        // If the log file name was not provided through configuration
        if (outputLogFileName == null || outputLogFileName.isEmpty()) {
            // Extract the merged filename
            String mergedPdfName = FileSystem.getFilename(mergedPdfFilePath).toLowerCase();

            // Remove the PDF extension if necessary
            if (mergedPdfName.endsWith(CONFIG_FLAG_PDF_EXTENSION)) {
                StringBuffer buffer = new StringBuffer(mergedPdfName);
                logFileName = buffer.substring(0, mergedPdfName.lastIndexOf(CONFIG_FLAG_PDF_EXTENSION));
            }
        }
        // Name was provided through configuration
        else {
            logFileName = outputLogFileName;
        }

        // If it does not end with correct extension
        if (!logFileName.toLowerCase().endsWith(CONFIG_FLAG_LOG_EXTENSION)) {
            // Add extension and build log file name
            logFileName = logFileName + CONFIG_FLAG_LOG_EXTENSION;
        }

        // Build the log file path
        StringBuffer logFilepath = new StringBuffer(outputDirectory).append(logFileName);

        // Return the created file name
        LOG.info("Log file name '" + logFilepath.toString() + "' built.");
        return logFilepath.toString();
    }

    /**
     * Get the elapsed time between two date.
     * <p>
     * Retrieve the elapsed time between start date and end date. The unit used is
     * milliseconds.
     * </p>
     *
     * @param startDate The start date.
     * @param endDate   The end date.
     * @return The elapsed time.
     */
    private static long getElapsedTime(final Date startDate, final Date endDate) {
        long time = endDate.getTime() - startDate.getTime();
        return time;
    }

    /**
     * Get the formatted elapsed time between two date.
     * <p>
     * Retrieve the elapsed time between start date and end date. The units used
     * are hours, minutes, seconds. Format use is HH:MM:SS.
     * </p>
     *
     * @param startDate The start date.
     * @param endDate   The end date.
     * @return The formatted elapsed time.
     */
    @SuppressWarnings("unused")
    private static String getFormattedElapsedTime(final Date startDate, final Date endDate) {
        long time = endDate.getTime() - startDate.getTime();
        time = time / 1000;
        String format = String.format("%%0%dd", 2);
        String seconds = String.format(format, time % 60);
        String minutes = String.format(format, (time % 3600) / 60);
        String hours = String.format(format, time / 3600);
        return hours + ":" + minutes + ":" + seconds;
    }


    private static String getFormattedElapsedTime(final long elapsedTime) {
        long time = elapsedTime / 1000;
        String format = String.format("%%0%dd", 2);
        String seconds = String.format(format, time % 60);
        String minutes = String.format(format, (time % 3600) / 60);
        String hours = String.format(format, time / 3600);
        return hours + ":" + minutes + ":" + seconds;
    }

    /**
     * Log the application configuration with internal logger if logging level is
     * debug.
     * <p>
     * Log the default configuration read from the properties file and the command
     * line arguments received through the internal logger if the logging level
     * has been set to debug.
     * </p>
     *
     * @param commandLineArgs The command line arguments received.
     */
    private static void logApplicationParameters(String[] commandLineArgs) {
        // Log the properties file values
        if (config != null) {
            if (!config.isEmpty()) {
                // Log the default configuration read from properties files
                LOG.debug("Default configuration from " + CONFIGURATION_FILE + ": ");
                for (Object configurationKey : config.keySet()) {
                    LOG.debug((String) configurationKey + "=" + config.getProperty((String) configurationKey));
                }
            }
        }

        // Log the application arguments
        if (commandLineArgs != null) {
            StringBuffer commandLineLog = new StringBuffer("Command line arguments : ");
            for (String commandLineArg : commandLineArgs) {
                commandLineLog = commandLineLog.append(" " + commandLineArg);
            }
            LOG.debug(commandLineLog.toString());
        }
    }

    /**
     * The input directory to search for PDFs.
     */
    private static String inputDirectory = null;

    /**
     * The output directory to store output PDF file.
     */
    private static String outputDirectory = null;

    /**
     * Is the output directory the same one as the input
     */
    private static boolean outputDirectoryIsInputDirectory = false;

    /**
     * The output PDF file name.
     */
    private static String outputPdfFileName = null;

    /**
     * The log file name.
     */
    private static String outputLogFileName = null;

    /**
     * Is the PDF output filename based on first input PDF name ?
     */
    private static boolean outputPdfFileNameBasedOnInput = true;

    /**
     * Is the PDF output filename based on a part of input PDF name once a split
     * has been done ?
     */
    private static boolean outputPdfFileNameBasedOnIDSplit = false;

    /**
     * The regular expression used to split the PDF input file name in order to
     * build the output PDF file name.
     */
    private static String outputPdfFileNameBasedOnIDSplitRegex = "\\.";

    /**
     * The index of the field the PDF input file name once it has been splitted in
     * order to build the output PDF file name.
     */
    private static int outputPdfFileNameBasedOnIDSplitIndex = 1;

    /**
     * Is the PDF output filename based on an extracted part of input PDF name ?
     */
    private static boolean outputPdfFileNameBasedOnIDExtract = false;

    /**
     * The index of the character within the PDF input file name extract should be
     * made in order to build the output PDF file name.
     */
    private static int outputPdfFileNameBasedOnIDExtractFrom = 0;

    /**
     * The extract length of the PDF input file name should used to build the
     * output PDF file name.
     */
    private static int outputPdfFileNameBasedOnIDExtractLength = 8;

    /**
     * Flag that indicates if resources have to be optimized while PDF are being
     * merged.
     */
    private static boolean mergePdfOptimizingResourcesEnabled = false;

    /**
     * Command line arguments
     */
    private static Properties config = null;

    /**
     * The command line arguments
     */
    private static JSAPResult commandLineArguments = null;

    /**
     * The name of configuration files.
     */
    private static final String CONFIGURATION_FILE = "MergingPDF.properties";
    private static final String CONFIGURATION_FILE_LOG4J = "log4j.properties";

    /**
     * Constants defining useful system properties
     */
    private static final String SYSTEM_PROPERTY_DIRECTORY_HOME_KEY = "mergingpdf.home.dir";
    private static final String SYSTEM_PROPERTY_JAVA_VERSION_KEY = "java.version";

    /**
     * Constants defining configuration KEY names
     */
    private static final String CONFIG_KEY_LOG_DEBUG = "application.log.debug";
    private static final String CONFIG_KEY_DISPLAY_PROGESS = "application.display.progress";
    private static final String CONFIG_KEY_INPUT_DIR = "paths.input.directory";
    private static final String CONFIG_KEY_INPUT_RECURSIVE_SEARCH = "paths.input.recursive_search";
    private static final String CONFIG_KEY_OUTPUT_DIR = "paths.output.directory";
    private static final String CONFIG_KEY_OUTPUT_LOG_NAME = "output.log.name";
    private static final String CONFIG_KEY_OUTPUT_PDF_NAME = "output.pdf.name";
    private static final String CONFIG_KEY_OUTPUT_PDF_ID_SPLIT_REGEX = "output.pdf.id.split.regex";
    private static final String CONFIG_KEY_OUTPUT_PDF_ID_SPLIT_IDX = "output.pdf.id.split.index";
    private static final String CONFIG_KEY_OUTPUT_PDF_ID_EXTRACT_FROM = "output.pdf.id.extract.from";
    private static final String CONFIG_KEY_OUTPUT_PDF_ID_EXTRACT_LENGTH = "output.pdf.id.extract.len";
    private static final String CONFIG_KEY_MERGE_PDF_RESOURCES_OPTIMIZING = "merge.pdf.res.optimizing";
    private static final String CONFIG_FLAG_TRUE = "T";
    private static final String CONFIG_FLAG_FALSE = "F";
    private static final String CONFIG_FLAG_PDF_EXTENSION = ".pdf";
    private static final String CONFIG_FLAG_LOG_EXTENSION = ".log";
    private static final String CONFIG_FLAG_OUTPUT_LOG_NAME = "merge.log";

    /**
     * A constant for time formatting
     */
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");

    /**
     * Log4J Logger instance.
     */
    private static final Log LOG = LogFactory.getLog(MergePDF.class);
}

