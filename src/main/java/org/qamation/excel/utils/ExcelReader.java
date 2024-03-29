package org.qamation.excel.utils;

import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import org.qamation.commons.utils.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;


public class ExcelReader {
    private static final Logger log = LoggerFactory.getLogger(ExcelReader.class);
    private Workbook workBook;
    private int activeSheetIndex;
    private String fileName;
    private String originalFileName;
    private Sheet sheet;
    private FormulaEvaluator evaluator;
    private String[] fieldNames;
    private int rowSize;
    private File theFile;
    private int iteratorInitPosition;
    private ShutDownHook hook;


    public ExcelReader(String fileName, int sheetIndex) {
        init(fileName, sheetIndex);
        this.iteratorInitPosition = 1;
        this.fieldNames = readFirstLine();
        this.originalFileName = fileName;
    }

    public ExcelReader(String fileName, int sheetIndex, String[] headers) {
        init(fileName, sheetIndex);
        this.iteratorInitPosition = 0;
        this.fieldNames = headers;
    }


    public ExcelReader(String fileName) {
        this(fileName, 0);
    }

    public ExcelReader(String fileName, String[] headers) {
        this(fileName, 0, headers);
    }

    public Iterator<String[]> getIterator() {
        final int initPosition = this.iteratorInitPosition;
        return new Iterator<String[]>() {
            private int cursor = initPosition;
            @Override
            public boolean hasNext() {
                return cursor < getNumberOfLinesInActiveWorkSheet();
            }

            @Override
            public String[] next() {
                return readValuesFromLine(cursor++);
            }
        };
    }

    public String[][] getData() {
        int lines = getNumberOfLinesInActiveWorkSheet();
        String[][] data = new String[lines][];
        for (int i = 0; i < lines; i++) {
            data[i] = readValuesFromLine(i);
        }
        return data;
    }

    public int getNumberOfLinesInActiveWorkSheet() {
        return sheet.getLastRowNum() + 1;
    }

    public String[] readValuesFromLine(int index) {
        Row row = getRow(index);
        if (row == null) {
            return createEmptyRow();
        }
        return convertRowToStringArray(row);
    }

    public String getFileName() {
        return fileName;
    }

    public int getActiveSheetIndex() {
        return activeSheetIndex;
    }

    public void closeWorkBook() {
        closeBook();
        deleteFile();
        removeShutDownHook(hook);
    }

    public String[] getFieldNames() {
        return fieldNames;
    }

    private void closeBook() {
        try {
            if (workBook != null) {
                workBook.close();
                workBook = null;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            throw new RuntimeException("Unable to close workbook for file: " + theFile.getPath() + "\n" + ex.getMessage());
        }
    }

    private void deleteFile() {
        if (theFile.exists()) {
            try {
                if (!theFile.delete())
                    throw new RuntimeException("File "+theFile.getName()+" could not be successfully deleted.");
            }
            catch (SecurityException ex) {
                ex.printStackTrace();
                throw new RuntimeException("Not enough permissions to delete "+ theFile.getName()+"\n",ex);
            }
        }
    }

    private String[] readFirstLine() {
        int headerLength = getHeaderLineSize();
        String[] headers = new String[headerLength];
        for (int i = 0; i < headerLength; i++) {
            Cell c = sheet.getRow(0).getCell(i, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            headers[i] = getStringValueFromCell(c);
        }
        return headers;
    }

    private int getHeaderLineSize() {
        return sheet.getRow(0).getPhysicalNumberOfCells();
    }

    private Row getRow(int rowNumber) {
        if (rowNumber < 0 || rowNumber > sheet.getLastRowNum())
            throw new RuntimeException("Row index cannot be less than 0 or greater than number of rows in the current sheet.");
        return sheet.getRow(rowNumber);
    }

    private String[] createEmptyRow() {
        String[] values = new String[rowSize];
        for (int i = 0; i < rowSize; i++) {
            values[i] = "";
        }
        return values;
    }

    private String[] convertRowToStringArray(Row row) {
        String[] vals = new String[this.rowSize];
        for (int i = 0; i < this.rowSize; i++) {
            Cell c = row.getCell(i, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            vals[i] = getStringValueFromCell(c);//c.getStringCellValue();
        }
        return vals;
    }

    private String getStringValueFromCell(Cell c) {
        switch (c.getCellType()) {
            case BLANK:
                return "";
            case STRING:
                return c.getStringCellValue();
            case BOOLEAN: {
                if (c.getBooleanCellValue()) return "true";
                return "false";
            }
            case ERROR:
                return "ERROR: " + String.valueOf(c.getErrorCellValue());
            case FORMULA: {
                return evaluateCell(c);
            }
            case NUMERIC: {
                if (DateUtil.isCellDateFormatted(c)) {
                    return String.valueOf(c.getDateCellValue());
                } else
                    return String.valueOf(c.getNumericCellValue());
            }
            default:
                return String.valueOf(c);
        }
    }

    private String evaluateCell(Cell c) {
        Cell newCell = evaluator.evaluateInCell(c);
        if (newCell != null)
            return getStringValueFromCell(newCell);
        return "";
    }

    private void init(String fileName, int index) {
        try {
            this.fileName = getTempFileName(fileName);
            this.theFile = createFile(this.fileName);
            this.workBook = createWorkBook(theFile);
            if (checkIndex(index, workBook.getNumberOfSheets()))
                throw new RuntimeException("Sheet index cannot be less than 0 or hight than available sheets.");
            this.activeSheetIndex = index;
            this.sheet = workBook.getSheetAt(activeSheetIndex);
            this.rowSize = getHeaderLineSize();
            this.evaluator = workBook.getCreationHelper().createFormulaEvaluator();
            this.hook = new ShutDownHook(this);
            addShutDownHook(hook);
        } catch (Exception ex) {
            ex.printStackTrace();
            log.error(ex.getMessage());
            throw new RuntimeException("Unable to create a workbook from " + fileName,ex);
        }
    }

    private String getTempFileName(String originalName) {
        return FileUtils.createTempFile(originalName);
    }

    private File createFile(String name) {
        return new File(name);
    }

    private Workbook createWorkBook(File file) throws IOException {
        return WorkbookFactory.create(file);
    }

    private boolean checkIndex(int givenSheetIndex, int workbookSize) {
        return (givenSheetIndex < 0 || givenSheetIndex > workbookSize);
    }

    private void addShutDownHook(ShutDownHook hook) {
        Runtime.getRuntime().addShutdownHook(hook);
    }

    private void removeShutDownHook(ShutDownHook hook) {
        Runtime.getRuntime().removeShutdownHook(hook);
    }


    public String getOriginalFileName() {
        return originalFileName;
    }

    private final class ShutDownHook extends Thread {
        private ExcelReader reader;

        ShutDownHook(ExcelReader reader) {
            this.reader = reader;
        }

        @Override
        public void run() {
            reader.closeBook();
            reader.deleteFile();
            reader = null;
        }
    }
}
