package edu.arizona.cs;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.store.FSDirectory;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.CoreDocument;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.simple.Sentence;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class WatsonQueryEngine {
	//==Application Setup Components==
      //File paths
	String inputFilePath = "";
	private final static String INDEX_PATH = "src\\main\\resources\\index.lucene";
	  //Application indicators
	boolean isDebug = true;
    boolean indexExists = false;
      //Index access components
    StandardAnalyzer analyzer = new StandardAnalyzer();
    FSDirectory index;
    
    //Purpose: Instantiate the query engine object using the given document file path and build the document index
    public WatsonQueryEngine(String inputFile){
        //Assign the input file path
    	this.inputFilePath = inputFile;
        
        //Check if the file index exists and build the index if it doesn't
        File file = new File(INDEX_PATH);
        if (file.exists()) {
        	try {this.index = FSDirectory.open(Paths.get(INDEX_PATH));}
        	catch (IOException e) {System.err.println("==QueryEngine(String inputFile): The file for the index directory wasn't able to be opened or created || " + e); System.exit(1);}
        	this.indexExists = true;
        }
        else {
        	this.indexExists = false;
        	try {buildIndex();}
        	catch (IOException e) {System.err.println("==QueryEngine(String inputFile): The file for the index directory wasn't able to be opened or created || " + e); System.exit(1);}
        }
    }

    //Purpose: Generate the index of the documents in the input file(s)
    private void buildIndex() throws IOException {
        //Get input file(s) from resources folder
        File[] files = {new File(this.inputFilePath)};
        if (files[0].isDirectory()) {files = files[0].listFiles();}
        
    	//Retrieve the document(s) in each file
        for (int i = 0; i< files.length; i++) {
        	//Contents of the file
        	String file = Files.readString(Paths.get(files[i].toURI()));
        	
        	//Generate a writer to add documents to the index
        	this.index = FSDirectory.open(Paths.get(INDEX_PATH));
        	IndexWriter w = new IndexWriter(this.index, new IndexWriterConfig(this.analyzer));
        	int contentEnd = 0;
        	int contentStart = 0;
        	while (contentEnd + 2 < file.length()) {
        		//Assign the start location for the content of the next document
	        	contentStart = file.indexOf("]]\n\n", contentEnd) + 4;
	        	
	            //Derive the document ID and contents from the document input line
	        	String docName = "";
	        	if (contentEnd == 0) {file.substring(contentEnd + 2, contentStart - 4);}
	        	else {docName = file.substring(contentEnd + 5, contentStart - 4);}

        		//Assign the content end position for the next document and fill a string with the document contents
	        	int offset = 0;
	        	while ((contentEnd = file.indexOf("\n\n\n[[", contentStart + offset)) == file.indexOf("\n\n\n[[File:", contentStart + offset) ||
	        		   (contentEnd = file.indexOf("\n\n\n[[", contentStart + offset)) == file.indexOf("\n\n\n[[Image:", contentStart + offset)) {
		        	if (contentEnd == -1) {contentEnd = file.length() - 1; break;}
		        	else {offset += 10;}
	        	}
	        	
	        	//Derive the contents based on the boundaries evaluated
	        	String contents = file.substring(contentStart, contentEnd);
	        	
	        	//Lemmatize and setup the document for indexing
	        	Properties props = new Properties();
	        	//props.setProperty("annotators", "tokenize,ssplit,pos");		//Control
	        	props.setProperty("annotators", "tokenize,ssplit,pos,lemma");	//Lemmatization
	        	StanfordCoreNLP pipeline = new StanfordCoreNLP(props);
	        	CoreDocument document = new CoreDocument(contents);
	        	pipeline.annotate(document);
	        	
	            //Generate an index for the document and it's contents
	            addDoc(w, docName, document);
	        	
	        	//Debug output
	            if (this.isDebug == true) {System.out.println(String.format("Document Added || [%s] : %s", docName, contents));}
	       }
            //Close the index writer and file scanner objects
            w.close();
        }
        this.indexExists = true;
    }
    
    //Purpose: Add a document and all the terms that are contained in it to the index specified
    private void addDoc(IndexWriter w, String docId, CoreDocument passedDoc) throws IOException {
    	//Generate a new document
    	Document doc = new Document();
    	
    	//Fill the document with any terms in the document
    	doc.add(new StringField("docid", docId, Field.Store.YES));
    	for (CoreLabel token : passedDoc.tokens()) {
    		String word = token.value();
    		
    		//Stem the word for the index	//Stemming
    		/*Stemmer s = new Stemmer();
    		word = s.stem(word);*/
    		
    		//Write the document terms to the lucene document
    		doc.add(new TextField("term", word, Field.Store.YES));
    	}
    	
    	//Add the document to the index
    	w.addDocument(doc);
    }

    //Purpose: Query a given parsing string across the current index
    public List<ResultClass> query(int docCount, String query) {
		ArrayList<ResultClass> result = new ArrayList<ResultClass>();
		
		try {
			//query = query.replaceAll("\\p{Punct}", "");	//Control
			//Lemmatize the query							//Lemmatization
			Sentence qPassed = new Sentence(query);
			query = qPassed.lemmas().toString().replaceAll("\\p{Punct}", "");
			
			//Stem the query								//Stemmer
    		/*Stemmer s = new Stemmer();
			for (String word : query.split("\\s+")) {
				query += s.stem(word) + " ";
			}
			query = query.trim();*/
			
			//Generate a query to navigate given the passed query string
			Query q = new QueryParser("term", this.analyzer).parse(query);
			
			//Instantiate the necessary reader and searcher methods for query
			IndexReader reader = DirectoryReader.open(this.index);
			IndexSearcher searcher = new IndexSearcher(reader);
			//searcher.setSimilarity(new ClassicSimilarity());		//TF-IDF search method
			
			//Retrieve the query results
			TopDocs docs = searcher.search(q, docCount);
			ScoreDoc[] hits = docs.scoreDocs;
			
			//Iterate over all of the documents returned from the query
			for (int i = 0; i < hits.length; i++) {
				//Add the document to the result list
				ResultClass temp = new ResultClass();
				temp.DocName = searcher.doc(hits[i].doc);
				temp.docScore = hits[i].score;
				result.add(temp);
				
				//Debug output
	            if (this.isDebug == true) {System.out.println("    Document query: " + temp.DocName.get("docid") + ", " + temp.docScore);}
			}
		} catch (ParseException | IOException e) {
			System.err.println("query(): Failure to parse query - " + query);
		}
		
    	return result;
    }
    
    //Purpose: Establish an WatsonQueryEngine object that indexes the wiki-data and run a known set of queries to measure performance
    public static void main(String[] args ) {
        try {
            //Setup an index of the wiki-data
        	String fileName = "src\\main\\resources\\wiki-data";
            System.out.println("********Project - IBM Watson");
            WatsonQueryEngine wqe = new WatsonQueryEngine(fileName);
            
            //Run queries with known answers and evaluate the correct returns
            fileName = "src\\main\\resources\\questions.txt";
            int correct = 0;
            List<String> lines = Files.readAllLines(Paths.get(fileName));
            for (int i = 0; i< lines.size(); i += 4) {
            	String category = lines.get(i);
            	String question = lines.get(i + 1);
            	String answer = lines.get(i + 2);
            	
            	List<ResultClass> result = wqe.query(3, category.trim() + " " + question);
            	if (!result.isEmpty() &&  result.get(0).DocName.get("docid").equalsIgnoreCase(answer)) {
            		System.out.println(String.format("  Correct Answer: %s", answer));
            		correct++;
            	}
            	else {System.out.println(String.format("      Expected Answer: %s", answer));}
            }
            
            System.out.println(String.format("Correct Answers: %d\nPossible Answers: %d", correct, (lines.size()/4)));
        }
        catch (Exception ex) {
            System.err.println(ex.getMessage());
        }
    }
}
