import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jdom.Document;
import org.jdom.Element;
import org.jdom.output.XMLOutputter;
import org.jdom.xpath.XPath;

import com.ephesoft.dcma.script.IJDomScript;
import com.ephesoft.dcma.util.FileUtils;
import com.ephesoft.dcma.util.XMLUtil;

/**
 * The <code>ScriptExtraction</code> class represents the ScriptExtraction structure. Writer of scripts plug-in should implement this
 * IScript interface to execute it from the scripting plug-in. Via implementing this interface writer can change its java file at run
 * time. Before the actual call of the java Scripting plug-in will compile the java and run the new class file.
 * 
 * @author Ephesoft
 * @version 1.0
 */
public class ScriptExtraction implements IJDomScript {

	private static final String BATCH_LOCAL_PATH = "BatchLocalPath";
	private static final String BATCH_INSTANCE_ID = "BatchInstanceIdentifier";
	private static final String EXT_BATCH_XML_FILE = "_batch.xml";
	private static String ZIP_FILE_EXT = ".zip";

	public Object execute(Document documentFile, String methodName, String docIdentifier) {
		Exception exception = null;
		try {
			System.out.println("*************  Inside ScriptExtraction scripts.");
			System.out.println("*************  Start execution of the ScriptExtraction scripts.");

			if (null == documentFile) {
				System.out.println("Input document is null.");
			}
			
			// get the batch instance ID
			Element e = (Element) XPath.selectSingleNode(documentFile, "/Batch/BatchInstanceIdentifier");
			String batchInstanceId = e.getText();

			// get the path to the ephesoft system folder
			e = (Element) XPath.selectSingleNode(documentFile, "/Batch/BatchLocalPath");
			String batchLocalPath = e.getText();
			
			// for each document
			@SuppressWarnings("unchecked")
			List<Element> receipts = XPath.selectNodes(documentFile, "/Batch/Documents/Document[Type='Recipt']");
			for (Element receipt : receipts) {
				
				Element table = (Element) XPath.selectSingleNode(receipt, "DataTables/DataTable[Name='Items']");
				
				// for each page of the document
				@SuppressWarnings("unchecked")
				List<Element> pages = XPath.selectNodes(receipt, "Pages/Page");
				for (Element page : pages) {
					
					// find the OCR output for this page
					String pageId = page.getChildText("Identifier");
					String pathToHocrZip = batchLocalPath + File.separator + 
							batchInstanceId + File.separator + 
							batchInstanceId + "_" + pageId + "_HOCR.xml";
					String hocrFilename = batchInstanceId + "_HOCR.xml";
					
					// for each line of text on the page
					Pattern firstLinePattern = Pattern.compile("^(.*)\\s+\\$\\s+(\\d+\\.\\d\\d)\\s+$");
					Pattern secondLinePattern = Pattern.compile("^.*:\\s(.*)\\s*$");
					Pattern lastLinePattern = Pattern.compile("^.*Discount.*\\(\\$\\s+(\\d+\\.\\d\\d)\\)\\s*$");
					Iterator<String> i = parseHOCR(pathToHocrZip, hocrFilename).iterator();
					while (i.hasNext()) {
						
						// does the line end with a dollar amount?  
						Matcher firstLineMatcher = firstLinePattern.matcher(i.next());
						if (firstLineMatcher.matches()) {
							String description = firstLineMatcher.group(1);
							String amount = firstLineMatcher.group(2);
							String partNumber = "";
							String discount = "";
							
							// if the dollar amount is a total, we're done with line items
							if (description.contains("Total")) {
								break;
							}
							
							// if there's another line, it's got the part number
							if (i.hasNext()) {
								Matcher secondLineMatcher = secondLinePattern.matcher(i.next());
								if (secondLineMatcher.matches()) {
									partNumber = secondLineMatcher.group(1);
								}
								
								// if there's another line, ignore it
								if (i.hasNext()) {
									i.next();
									
									// if there's another line, ignore it
									if (i.hasNext()) {
										i.next();
										
										// if there's another line, get the discount
										if (i.hasNext()) {
											Matcher lastLineMatcher = lastLinePattern.matcher(i.next());
											if (lastLineMatcher.matches()) {
												discount = lastLineMatcher.group(1);
											}
										}
									}
								}
							}
							
							System.out.println("Desc: " + description);
							System.out.println("Amount: " + amount);
							System.out.println("Part: " + partNumber);
							System.out.println("Discount: " + discount);
							System.out.println();
							
							addRow(table, new String[] {description, amount, partNumber, discount});
						}
					} // end for each line of text on the page
				} // end for each page
			} // end for each receipt
		
			boolean isWrite = true;
			// write the document object to the XML file.
			if (isWrite) {
				writeToXML(documentFile);
				System.out.println("*************  Successfully write the xml file for the ScriptExtraction scripts.");
				System.out.println("*************  End execution of the ScriptExtraction scripts.");
			}
		} catch (Exception e) {
			System.out.println("*************  Error occurred in scripts." + e.getMessage());
			exception = e;
		}
		return exception;
	}
	
	public List<String> parseHOCR(String pathToHocrZip, String hocrFilename) throws Exception {
	
		// parse the OCR output
		Document hocrDom = XMLUtil.createJDOMDocumentFromInputStream(FileUtils.getInputStreamFromZip(pathToHocrZip, hocrFilename));
		
		// get each word from the OCR output
		@SuppressWarnings("unchecked")
		List<Element> words = XPath.selectNodes(hocrDom, "/HocrPages/HocrPage/Spans/Span");
		
		// sort the words vertically
		Collections.sort(words, new Comparator<Element>() {
			public int compare(Element wordA, Element wordB) {
				try {
					Element yA = (Element) XPath.selectSingleNode(wordA, "Coordinates/y0");
					Element yB = (Element) XPath.selectSingleNode(wordB, "Coordinates/y0");
					return Integer.parseInt(yA.getText()) - Integer.parseInt(yB.getText());
				} 
				catch (Exception x) {
					return 0;
				}
			}});
		
		List<List<Element>> lines = new LinkedList<List<Element>>();
		List<Element> line = new LinkedList<Element>();
		int FUDGE_FACTOR = 20;
		
		// for each word
		int lineStartY = -1;
		for (Element word : words) {
			Element yElem = (Element) XPath.selectSingleNode(word, "Coordinates/y0");
			int y = Integer.parseInt(yElem.getText());
			
			// if this is a new line, add the word to the line
			if (lineStartY == -1) {
				line.add(word);
				lineStartY = y;
			}
			
			// if this is not a new line
			else {
				// if it should it be a new line, end the last line and start a new one
				if (y - lineStartY > FUDGE_FACTOR) {
					lines.add(line);
					line = new LinkedList<Element>();
					lineStartY = y;
				}
				
				// add the word to the line
				line.add(word);
			}
		}
		
		// add the last line of text
		if (line.size() > 0) {
			lines.add(line);
		}
		
		// for each line
		for (List<Element> unsortedLine : lines) {
			// sort the words horizontally
			Collections.sort(unsortedLine, new Comparator<Element>() {
				public int compare(Element wordA, Element wordB) {
					try {
						Element xA = (Element) XPath.selectSingleNode(wordA, "Coordinates/x0");
						Element xB = (Element) XPath.selectSingleNode(wordB, "Coordinates/x0");
						return Integer.parseInt(xA.getText()) - Integer.parseInt(xB.getText());
					} 
					catch (Exception x) {
						return 0;
					}
				}});
		}
		
		// convert the words to strings
		List <String> text = new LinkedList<String>();
		for (List<Element> sortedLine : lines) {
			String lineOfText = "";
			for (Element word : sortedLine) {
				lineOfText += word.getChildText("Value") + " ";
			}
			text.add(lineOfText);
			System.out.println(lineOfText);
		}
	
		return text;
	}
	
	public void addRow(Element element, String[] cols) {
		Element rowsElement = element.getChild("Rows");
		
		// create a new row
		Element rowElement = appendChildNode(rowsElement, "Row");

		// create new coordinates
		Element coords = appendChildNode(rowElement, "RowCoordinates");
		appendChildNode(coords, "x0", "1");  // TODO: use correct coordinates
		appendChildNode(coords, "y0", "50");
		appendChildNode(coords, "x1", "1");
		appendChildNode(coords, "y1", "50");
		
		// it's really "mannual" in the XML
		appendChildNode(rowElement, "MannualExtraction", "false");  
		
		Element colsElement = appendChildNode(rowElement, "Columns");

		for (int i = 0; i < cols.length; i++) {
			addCell(colsElement, cols[i]);
		}
	}
	
	protected void addCell(Element colsElement, String value) {
		Element col = appendChildNode(colsElement, "Column");
		appendChildNode(col, "Value", value);
		appendChildNode(col,  "Confidence", "100");
		
		Element coordsList = appendChildNode(col, "CoordinatesList");
		Element coords = appendChildNode(coordsList, "Coordinates");

		appendChildNode(coords, "x0", "1");  // TODO: use correct coordinates
		appendChildNode(coords, "y0", "50");
		appendChildNode(coords, "x1", "1");
		appendChildNode(coords, "y1", "50");
		
		appendChildNode(col, "Page", "PG0"); // TODO: use real page num
		appendChildNode(col, "FieldOrderNumber", "0");
		appendChildNode(col, "Valid", "false");
		appendChildNode(col, "ValidationRequired", "false");
		
		appendChildNode(col, "AlternateValues");
	}
	
	static protected Element appendChildNode(Element parent, String name, String value)  {
		Element child = new Element(name);
		child.setText(value);
		parent.addContent(child);
		return child;
	}
	
	static protected Element appendChildNode(Element parent, String name) {
		Element child = new Element(name);
		parent.addContent(child);
		return child;
	}
	

	/**
	 * The <code>writeToXML</code> method will write the state document to the XML file.
	 * 
	 * @param document {@link Document}.
	 */
	private void writeToXML(Document document) {
		String batchLocalPath = null;
		List<?> batchLocalPathList = document.getRootElement().getChildren(BATCH_LOCAL_PATH);
		if (null != batchLocalPathList) {
			batchLocalPath = ((Element) batchLocalPathList.get(0)).getText();
		}

		if (null == batchLocalPath) {
			System.err.println("Unable to find the local folder path in batch xml file.");
			return;
		}

		String batchInstanceID = null;
		List<?> batchInstanceIDList = document.getRootElement().getChildren(BATCH_INSTANCE_ID);
		if (null != batchInstanceIDList) {
			batchInstanceID = ((Element) batchInstanceIDList.get(0)).getText();

		}

		if (null == batchInstanceID) {
			System.err.println("Unable to find the batch instance ID in batch xml file.");
			return;
		}

		String batchXMLPath = batchLocalPath.trim() + File.separator + batchInstanceID + File.separator + batchInstanceID
				+ EXT_BATCH_XML_FILE;

		String batchXMLZipPath = batchXMLPath + ZIP_FILE_EXT;

		System.out.println("batchXMLZipPath************" + batchXMLZipPath);

		OutputStream outputStream = null;
		File zipFile = new File(batchXMLZipPath);
		FileWriter writer = null;
		XMLOutputter out = new XMLOutputter();
		try {
			if (zipFile.exists()) {
				System.out.println("Found the batch xml zip file.");
				outputStream = getOutputStreamFromZip(batchXMLPath, batchInstanceID + EXT_BATCH_XML_FILE);
				out.output(document, outputStream);
			} else {
				writer = new java.io.FileWriter(batchXMLPath);
				out.output(document, writer);
				writer.flush();
				writer.close();
			}
		} catch (Exception e) {
			System.err.println(e.getMessage());
		} finally {
			if (outputStream != null) {
				try {
					outputStream.close();
				} catch (IOException e) {
				}
			}
		}
	}

	public static OutputStream getOutputStreamFromZip(final String zipName, final String fileName) throws FileNotFoundException,
			IOException {
		ZipOutputStream stream = null;
		stream = new ZipOutputStream(new FileOutputStream(new File(zipName + ZIP_FILE_EXT)));
		ZipEntry zipEntry = new ZipEntry(fileName);
		stream.putNextEntry(zipEntry);
		return stream;
	}
	
	public static void main(String args[]){
		String zipFilePath = "C:\\Ephesoft\\SharedFolders\\ephesoft-system-folder\\BI14\\BI14_batch.xml";
		String xmlFilename = "BI14_batch.xml";
		try {
			Document doc = XMLUtil.createJDOMDocumentFromInputStream(FileUtils.getInputStreamFromZip(zipFilePath, xmlFilename));
			ScriptExtraction  se = new ScriptExtraction();
			se.execute(doc, null, null);
		} catch (Exception x) {
			x.printStackTrace();
		}
	}}
