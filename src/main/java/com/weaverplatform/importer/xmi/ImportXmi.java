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
import java.nio.file.Path;
import java.util.Map;

/**
 * Created by Jonathan Smit, Sysunite 2016
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

    try {

      String xmiContent = IOUtils.toString(importXmi.read());

      //replace ':' to ignore xml namespace errors while reading with xpath
      xmiContent = xmiContent.replaceAll("UML:", "UML.");

      XML doc = new XMLDocument(xmiContent);

      String xpath = "//XMI.content/UML.Model/UML.Namespace.ownedElement/UML.Package/UML.Namespace.ownedElement/UML.Package/UML.Namespace.ownedElement/UML.Class";
      
      HashMap<String, String> xmiClasses = importXmi.mapXmiClasses(doc.nodes(xpath));

      System.out.println(xmiClasses.size());

      importXmi.mapXmiClassesToWeaverIndividuals(xmiClasses);

      xpath = "//XMI.content/UML.Model/UML.Namespace.ownedElement/UML.Package/UML.Namespace.ownedElement/UML.Package/UML.Namespace.ownedElement/UML.Association";

      importXmi.mapXmiAssociationsToWeaverAnnotations(doc.nodes(xpath), xmiClasses);

    } catch (IOException e) {
      //catch IOUtils.toString()
      e.printStackTrace();
    }
  }

  /**
   * Loop trough xmi-associations and map them to weaver as annotations
   * @param xmiAssocations: list xmi nodes of type association
   * @param xmiClasses: hashmap with xmi-classes
   */
  public void mapXmiAssociationsToWeaverAnnotations(List<XML> xmiAssocations, HashMap<String, String> xmiClasses){
    for(XML association : xmiAssocations){

      //there exists nodes without attribute name
      if(notNull(getNamedItemOnNode(association, "name"))){

        //we need the name of the association later, so we save it locally
        String associationName = getValue(getNamedItemOnNode(association, "name"));

        //we go inside the association-tree looking for a specific one
        mapSpecificXmiAssociationToWeaverAnnotation(associationName, association, xmiClasses);

      }
    }
  }

  /**
   * map a specific association to weaver (as annotation) where its taggedValue equals Source.
   * @param associationName : label name for future annotation attribute label
   * @param currentAssociation : an XML document
   * @param xmiClasses : HashMap which contains the xmi Class id
   *                   which is the parent of this annotation used for weaver
   */
  public void mapSpecificXmiAssociationToWeaverAnnotation(String associationName, XML currentAssociation, HashMap<String, String> xmiClasses){

    String xpath = "//UML.Association.connection/UML.AssociationEnd";
    List<XML> associationEnds = currentAssociation.nodes(xpath);

    //we go a lever deeper within the association-tree
    for(XML associationEnd: associationEnds){

      //we need the individual id, that is known in the xmi-tree as attribute Type
      String type = getValue(getNamedItemOnNode(associationEnd, "type"));

      xpath = "//UML.ModelElement.taggedValue/UML.TaggedValue";
      List<XML> taggedValues = associationEnd.nodes(xpath);

      //at last, our deepest level within the association-tree, we now have specific one
      for(XML taggedValue : taggedValues){

        String taggedValueAttributeValue = getValue(getNamedItemOnNode(taggedValue, "value"));

        //only if the attribute is the SOURCE, we can create the annotation
        if(taggedValueAttributeValue.equals("source")){

          //now we found the right association, we use the saved Class id which equals the Type-attribute
          String classID = xmiClasses.get(type);

          //System.out.println("classID: " + classID + ", associationName: " + associationName);

          //create weaver annotation
          HashMap<String, Object> attributes = new HashMap<>();
          attributes.put("label", associationName);
          attributes.put("celltype", "individual");
          toWeaverAnnotation(attributes, classID);

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
        //System.out.println(pair.getKey() + " = " + pair.getValue());
        String xmiClassName = (String)pair.getValue();
        //save to weaver as Individual
        toWeaverIndividual(null, xmiClassName);
        //it.remove(); // avoids a ConcurrentModificationException
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

      String name = formatName(getNamedItemOnNode(xmiClass, "name"));
      String xmiID = getValue(getNamedItemOnNode(xmiClass, "xmi.id"));

      map.put(xmiID, name);
    }

    return map;
  }

  public boolean notNull(org.w3c.dom.Node node){
    if(node != null){
      return true;
    }
    return false;
  }

  public boolean notNull(String value){
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

    try{

      if(!hasPath(filePath)) {

        //read from class path test resource directory
        byte[] contents = FileUtils.readFileToByteArray(new File(getClass().getClassLoader().getResource(filePath).getFile()));
        InputStream in = new ByteArrayInputStream(contents);
        InputStream cont = new ByteArrayInputStream(IOUtils.toByteArray(in));
        return cont;

      }

      File f = new File(filePath);

      boolean isFile = f.exists();

      if(isFile){

        //read from unix path
        Path path = Paths.get(f.getAbsolutePath());
        byte[] data = Files.readAllBytes(path);

        InputStream in = new ByteArrayInputStream(data);
        InputStream cont = new ByteArrayInputStream(IOUtils.toByteArray(in));
        return cont;
      }

    }catch(Exception e) {
      System.out.println("cannot read!");
    }

    return null;

  }

  public boolean hasPath(String fileName){
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
  public org.w3c.dom.Node getNamedItemOnNode(XML doc, String attributeName){
    return doc.node().getAttributes().getNamedItem(attributeName);
  }

  /**
   * Gets a value from a node i.e. an node attribute value
   * @param node
   * @return String value
   */
  public String getValue(org.w3c.dom.Node node){
    return node.getTextContent();
  }

  public String formatName(org.w3c.dom.Node node){
    String textvalue = getValue(node);

    String[] split = textvalue.split(" ");

    StringBuffer stringBuffer = new StringBuffer();
    stringBuffer.append("ib:");

    for(String name : split){
      name = stripNonCharacters(name);
      name = toCamelCase(name);
      stringBuffer.append(name);
    }

    return stringBuffer.toString();
  }

  public String stripNonCharacters(String str){

    StringBuilder result = new StringBuilder();
    for(int i=0; i<str.length(); i++) {
      char tmpChar = str.charAt(i);
      if(Character.isLetter(tmpChar)){
        result.append(tmpChar);
      }
    }

    return result.toString();

  }

  public String toCamelCase(String str){
    str = str.toLowerCase();

    String firstChar = str.substring(0, 1);
    firstChar = firstChar.toUpperCase();

    String rest = str.substring(1, str.length());

    String newName = firstChar + rest;

    return newName;
  }

  /**
   * Creates an Weaver Individual
   * @param attributes: weaver entity attributes
   * @param id: weaver entity id
   * @return Weaver Entity
   */
  public Entity toWeaverIndividual(HashMap<String, Object> attributes, String id){

    HashMap<String, Object> defaultAttributes = new HashMap<>();
    attributes.put("name", "Unnamed");

    //create object
    Entity parent = weaver.add(attributes == null ? defaultAttributes : attributes, EntityType.INDIVIDUAL, id);

    //create first annotation collection
    Entity aAnnotions = weaver.add(new HashMap<String, Object>(), EntityType.COLLECTION, weaver.createRandomUUID());
    parent.linkEntity(RelationKeys.ANNOTATIONS, aAnnotions);

    //create collection properties
    Entity aCollection = weaver.add(new HashMap<String, Object>(), EntityType.COLLECTION, weaver.createRandomUUID());
    parent.linkEntity(RelationKeys.PROPERTIES, aCollection);

    return parent;
  }

  /**
   * Creates an Weaver Annotation
   * @param attributes
   * @param id
   * @return
   */
  public Entity toWeaverAnnotation(HashMap<String, Object> attributes, String id){

      //retrieve parent
      Entity parent = weaver.get(id);

      //retrieve annotions collection
      Entity aAnnotations = parent.getRelations().get(RelationKeys.ANNOTATIONS);

      //create first annotation
      Entity annotation = weaver.add(attributes==null?new HashMap<String, Object>():attributes, EntityType.ANNOTATION, weaver.createRandomUUID());
      aAnnotations.linkEntity(annotation.getId(), annotation);

      return annotation;
  }

}