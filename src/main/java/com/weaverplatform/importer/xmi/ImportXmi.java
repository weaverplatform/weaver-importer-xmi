package com.weaverplatform.importer.xmi;

import com.jcabi.xml.XML;
import com.jcabi.xml.XMLDocument;
import com.weaverplatform.sdk.Entity;
import com.weaverplatform.sdk.EntityType;
import com.weaverplatform.sdk.RelationKeys;
import com.weaverplatform.sdk.Weaver;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Created by Jonathan Smit, Sysunite 2016
 *
 * This program is written to import xmi-data and map parts of it to Weaver objects by the weaver-sdk-java.
 */
public class ImportXmi {

  private Weaver weaver;
  private String filePath;

  /**
   * Constructor
   * @param weaverUrl connection string to Weaver
   * @param filePath specify as filename i.e. "filePath.xml" or unixpath i.e. "/usr/lib/input.xml"
   */
  public ImportXmi(String weaverUrl, String filePath) throws RuntimeException{
    if(notNull(weaverUrl) && notNull(filePath)){
      weaver = new Weaver();
      weaver.connect(weaverUrl);
      this.filePath = filePath;
    }else{
      throw new RuntimeException("one or more constructor arguments are null");
    }
  }

  /**
   * Run standalone
   * @param args
   * args[0] = weaver connection uri i.e. http://weaver:port
   * args[1] = filePath (see also: constructor @param filePath)
   */
  public static void main(String[] args){
    ImportXmi importXmi = new ImportXmi(args[0], args[1]);
    importXmi.run();
  }

  /**
   * The start method with custom operations on this class
   */
  public void run(){
    //read the xml file and get a formatted Jcabi XML document.
    XML xmldocument = getFormatedXML();

    //because we have formatted Jcabi XMLDocument, we can have special xpaths to the nodes we want
    String xpathToXmiClasses = "//XMI.content/UML.Model/UML.Namespace.ownedElement/UML.Package/UML.Namespace.ownedElement/UML.Package/UML.Namespace.ownedElement/UML.Class";
    String xpathToXmiAssociations = "//XMI.content/UML.Model/UML.Namespace.ownedElement/UML.Package/UML.Namespace.ownedElement/UML.Package/UML.Namespace.ownedElement/UML.Association";

    //map the xmi classes to a hashmap
    //let jcabi fetch the xmi class nodes by the right xpath
    HashMap<String, String> xmiClasses = mapXmiClasses(xmldocument.nodes(xpathToXmiClasses));

    //map the xmiClasses to weaver as weaver individuals
    mapXmiClassesToWeaverIndividuals(xmiClasses);

    //map them as annotations to weaver and link them to the xmiClasses
    //let jcabi fetch the associations by the right xpath
    mapXmiAssociationsToWeaverAnnotations(xmldocument.nodes(xpathToXmiAssociations), xmiClasses);
  }

  /**
   * return the file contents as jcabi XML document
   * @return XMLDocument
   */
  public XML getXML(){
    return new XMLDocument(getFileContents(read()));
  }

  /**
   * returns the file contents as jcabi XML document in a formatted way.
   * the jcabi XML Document has constructor argument getFormattedFileContents().
   * @return XMLDocument
   */
  public XML getFormatedXML(){
    return new XMLDocument(getFormatedFileContents(read()));
  }

  /**
   * returns the file contents - an InputStream- as String
   * @return String
   */
  public String getFileContents(InputStream contents){
    try {
      return IOUtils.toString(contents);
    }catch(IOException e) {
      System.out.println("IOUtils.toString() fail");
    }
    return null;
  }

  /**
   * Returns the file contents as String (modified original)
   * @return
   */
  public String getFormatedFileContents(InputStream contents){
    //replace ':' to ignore xml namespace errors while reading with xpath
    return getFileContents(contents).replaceAll("UML:", "UML.");
  }

  /**
   * Loop trough xmi-associations and map them to weaver as annotations
   * @param xmiAssocations: list xmi nodes of type association
   * @param xmiClasses: hashmap with xmi-classes
   */
  public void mapXmiAssociationsToWeaverAnnotations(List<XML> xmiAssocations, HashMap<String, String> xmiClasses){
    for(XML association : xmiAssocations){

      //there are associations without attribute name
      //we make sure the attribute name is available
      if(notNull(getAttributeAsNode(association, "name"))){

        //we need the name of the association later, so we save it locally
        String associationName = getValueFromNode(getAttributeAsNode(association, "name"));

        //we go inside the association-tree looking for a specific one
        mapSpecificXmiAssociationToWeaverAnnotation(associationName, association, xmiClasses);

      }
    }
  }

  /**
   * map a specific association to weaver (as annotation) when its taggedValue equals Source.
   * @param associationName : label name for future annotation attribute label
   * @param currentAssociation : an XML document
   * @param xmiClasses : HashMap which contains the xmi Class id
   *                   which is the parent of this annotation used for weaver
   */
  public void mapSpecificXmiAssociationToWeaverAnnotation(String associationName, XML currentAssociation, HashMap<String, String> xmiClasses){

    /** currentAssociation node
     *
     * <association>
     *     <connection>
     *         <end type=xmiId>
     *            <taggedValues>
     *                <taggedValue value=source></taggedValue>
     *            </taggedValues>
     *         </end>
     *     </connection>
     * </association>
     *
     */

    String xpathToAssociationEndNodes = "//UML.Association.connection/UML.AssociationEnd";
    String xpathToTaggedValueNodes = "//UML.ModelElement.taggedValue/UML.TaggedValue";

    List<XML> associationEndNodes = currentAssociation.nodes(xpathToAssociationEndNodes);

    for(XML associationEndNode: associationEndNodes){

      List<XML> taggedValueNodes = associationEndNode.nodes(xpathToTaggedValueNodes);

      //we have multiple taggedValueNodes, so we need to loop trough them
      for(XML taggedValueNode : taggedValueNodes){

        String taggedAttribute = getValueFromNode(getAttributeAsNode(taggedValueNode, "value"));

        if(taggedAttribute.equals("source")){

          //the associationEndNode type attribute is the xmi-id.
          //we have the xmi-id as key stored in the xmiClasses hashmap.
          //now we want the corresponding xmiClassName
          //we fetch the name from the hashmap by the assocationEndNode type attribute as key
          String xmiName = xmiClasses.get(getValueFromNode(getAttributeAsNode(associationEndNode, "type")));

          //create weaver annotation
          HashMap<String, Object> attributes = new HashMap<>();
          attributes.put("label", associationName);
          attributes.put("celltype", "individual");
          toWeaverAnnotation(attributes, xmiName);

        }
      }

    }

  }

  /**
   * save the name of an xmi-class as weaver individual
   * @param xmiClasses
   */
  public void mapXmiClassesToWeaverIndividuals(HashMap<String, String> xmiClasses){
    Iterator it = xmiClasses.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry pair = (Map.Entry)it.next();
      String xmiClassName = (String)pair.getValue();
      //save to weaver as Individual
      toWeaverIndividual(null, xmiClassName);
    }
  }


  /**
   * Creates one hashmap from all xmi Classes
   * @param xmiClasses: xmi nodes list
   * @return HashMap<xmi-name, xmi-id> from every xmi Class
   */
  public HashMap<String, String> mapXmiClasses(List<XML> xmiClasses){
    HashMap<String, String> map = new HashMap<String, String>();
    for (XML xmiClass : xmiClasses) {

      String name = formatName(getAttributeAsNode(xmiClass, "name"));
      String xmiID = getValueFromNode(getAttributeAsNode(xmiClass, "xmi.id"));

      map.put(xmiID, name);
    }
    return map;
  }

  /**
   * check if an org.w3c.dom.Node is not null
   * @param node: org.w3c.dom.Node
   * @return true if not null, false if otherwise
   */
  private boolean notNull(org.w3c.dom.Node node){
    if(node != null){
      return true;
    }
    return false;
  }

  /**
   * Checks if a String is not null
   * @param value: String
   * @return true if not null, false if otherwise
   */
  private boolean notNull(String value){
    if(value != null){
      return true;
    }
    return false;
  }

  /**
   * Reads an xmi filePath from a classpath (test resource directory) or unixpath
   * @return InputStream on succes or null on failure
   */
  public InputStream read(){
    if(!hasPath(filePath)) {
      return createInputStream(readFromTestClassResourceDirectory(filePath));
    }
    return createInputStream(readFromUnixPath(filePath));
  }

  /**
   * reads a file from a unix path, returns the contents as byte array
   * @param path i.e. "/dir/user/file.xml"
   * @return byte[] result
   */
  private byte[] readFromUnixPath(String path){
    try {
      File f = new File(filePath);
      boolean isFile = f.exists();
      if(isFile){
        return Files.readAllBytes(Paths.get(f.getAbsolutePath()));
      }
    }catch(IOException e) {
      System.out.println("FileUtils.readAllBytes fail");
    }
    return null;
  }

  /**
   * reads a file from a class path test resource directory, returns the contents as byte array
   * @param path: i.e. "file.xml"
   * @return byte[] result
   */
  private byte[] readFromTestClassResourceDirectory(String path){
    try {
      return FileUtils.readFileToByteArray(new File(getClass().getClassLoader().getResource(filePath).getFile()));
    }catch(IOException e) {
      System.out.println("FileUtils.readFileToByteArray fail");
    }
    return null;
  }

  /**
   * Creates an inputstream object from a byte array
   * @param contents
   * @return InputStream on succes, null on failure
   */
  private InputStream createInputStream(byte[] contents){
    try {
      return new ByteArrayInputStream(IOUtils.toByteArray(new ByteArrayInputStream(contents)));
    }catch(IOException e){
      System.out.println("IOUtils.toByteArray() fail");
    }
    return null;
  }

  /**
   * checks if the file has a forward slash
   * @param fileName
   * @return
   */
  private boolean hasPath(String fileName){
    if(fileName.matches("(.*)/(.*)")){
      //seems the be an unix path
      return true;
    }
    return false;
  }

  /**
   * fetches a Node attribute and return that attribute as Node-object
   * @param doc
   * @param attributeName
   * @return
   */
  private org.w3c.dom.Node getAttributeAsNode(XML doc, String attributeName){
    return doc.node().getAttributes().getNamedItem(attributeName);
  }

  /**
   * Gets a value from a node i.e. an node attribute value
   * @param node
   * @return String value
   */
  private String getValueFromNode(org.w3c.dom.Node node){
    return node.getTextContent();
  }

  /**
   * Returns the textValue from a org.w3c.dom.Node as custom formatted String
   * @param node
   * @return String
   */
  private String formatName(org.w3c.dom.Node node){
    String[] partsOfNodeAttributeValue  = getValueFromNode(node).split(" ");
    StringBuffer newString = new StringBuffer().append("ib:");
    for(String partOfNodeAttributeValue : partsOfNodeAttributeValue){
      partOfNodeAttributeValue = toCamelCase(stripNonCharacters(partOfNodeAttributeValue));
      newString.append(partOfNodeAttributeValue);
    }
    return newString.toString();
  }

  /**
   * Ignores characters other then letters and return the result with letters only
   * @param str input
   * @return String formatted
   */
  private String stripNonCharacters(String str){
    StringBuilder result = new StringBuilder();
    for(int i=0; i<str.length(); i++) {
      char tmpChar = str.charAt(i);
      if(Character.isLetter(tmpChar)){
        result.append(tmpChar);
      }
    }
    return result.toString();
  }

  /**
   * Transform the first char of the string to capital, and all other characters to small.
   * @param str: String
   * @return String result
   */
  private String toCamelCase(String str){
    str = str.toLowerCase();
    String firstCharAsCapital = str.substring(0, 1).toUpperCase();
    String charactersWithoutFirstChar = str.substring(1, str.length());
    return (firstCharAsCapital + charactersWithoutFirstChar);
  }

  /**
   * Creates an Weaver Individual
   * @param attributes: weaver entity attributes
   * @param id: weaver entity id
   * @return Weaver Entity on succes or null on failure
   */
  public Entity toWeaverIndividual(HashMap<String, Object> attributes, String id){
    HashMap<String, Object> defaultAttributes = new HashMap<>();
    defaultAttributes.put("name", "Unnamed");
    try {
      //create object
      Entity parent = weaver.add(attributes == null ? defaultAttributes : attributes, EntityType.INDIVIDUAL, id);

      //create first annotation collection
      Entity aAnnotions = weaver.add(new HashMap<String, Object>(), EntityType.COLLECTION, weaver.createRandomUUID());
      parent.linkEntity(RelationKeys.ANNOTATIONS, aAnnotions);

      //create collection properties
      Entity aCollection = weaver.add(new HashMap<String, Object>(), EntityType.COLLECTION, weaver.createRandomUUID());
      parent.linkEntity(RelationKeys.PROPERTIES, aCollection);

      return parent;

    }catch(NullPointerException e){
      System.out.println("weaver connection error/node not found.");
    }
    return null;
  }

  /**
   * Creates an Weaver Annotation
   * @param attributes
   * @param id
   * @return Weaver Entity on succes or null on failure
   */
  public Entity toWeaverAnnotation(HashMap<String, Object> attributes, String id){
    try {
      //retrieve parent
      Entity parent = weaver.get(id);

      //retrieve annotions collection
      Entity aAnnotations = parent.getRelations().get(RelationKeys.ANNOTATIONS);

      //create first annotation
      Entity annotation = weaver.add(attributes == null ? new HashMap<String, Object>() : attributes, EntityType.ANNOTATION, weaver.createRandomUUID());
      aAnnotations.linkEntity(annotation.getId(), annotation);

      return annotation;

    }catch(NullPointerException e){
      System.out.println("weaver connection error/node not found.");
    }
    return null;
  }

}
