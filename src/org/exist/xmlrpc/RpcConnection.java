package org.exist.xmlrpc;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.TreeMap;
import java.util.Vector;
import java.util.WeakHashMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;

import org.apache.log4j.Logger;
import org.exist.EXistException;
import org.exist.Parser;
import org.exist.collections.Collection;
import org.exist.collections.triggers.TriggerException;
import org.exist.dom.ArraySet;
import org.exist.dom.DocumentImpl;
import org.exist.dom.DocumentSet;
import org.exist.dom.NodeProxy;
import org.exist.dom.NodeSet;
import org.exist.dom.SortedNodeSet;
import org.exist.memtree.NodeImpl;
import org.exist.parser.XPathLexer2;
import org.exist.parser.XPathParser2;
import org.exist.parser.XPathTreeParser2;
import org.exist.security.Permission;
import org.exist.security.PermissionDeniedException;
import org.exist.security.User;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.serializers.EXistOutputKeys;
import org.exist.storage.serializers.Serializer;
import org.exist.util.Configuration;
import org.exist.util.Occurrences;
import org.exist.util.SyntaxException;
import org.exist.util.serializer.DOMSerializer;
import org.exist.xpath.PathExpr;
import org.exist.xpath.StaticContext;
import org.exist.xpath.XPathException;
import org.exist.xpath.value.Item;
import org.exist.xpath.value.NodeValue;
import org.exist.xpath.value.Sequence;
import org.exist.xpath.value.SequenceIterator;
import org.exist.xpath.value.Type;
import org.exist.xupdate.Modification;
import org.exist.xupdate.XUpdateProcessor;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import antlr.collections.AST;

/**
 * This class implements the actual methods defined by {@link org.exist.xmlrpc.RpcAPI}.
 * 
 * @author Wolfgang Meier (wolfgang@exist-db.org)
 */
public class RpcConnection extends Thread {

	private final static Logger LOG = Logger.getLogger(RpcConnection.class);
	protected BrokerPool brokerPool;
	protected WeakHashMap documentCache = new WeakHashMap();
	protected Parser parser = null;
	protected boolean terminate = false;
	protected DocumentBuilder docBuilder = null;
	protected RpcServer.ConnectionPool connectionPool;
	protected TreeMap tempFiles = new TreeMap();

	public RpcConnection(Configuration conf, RpcServer.ConnectionPool pool)
		throws EXistException {
		super();
		connectionPool = pool;
		brokerPool = BrokerPool.getInstance();
		DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
		try {
			docBuilder = docFactory.newDocumentBuilder();
		} catch (ParserConfigurationException e) {
			LOG.warn(e);
			throw new EXistException(e);
		}
	}

	public void createCollection(User user, String name)
		throws Exception, PermissionDeniedException {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			Collection current = broker.getOrCreateCollection(name);
			LOG.debug("creating collection " + name);
			broker.saveCollection(current);
			broker.flush();
			//broker.sync();
			LOG.debug("collection " + name + " has been created");
		} catch (Exception e) {
			LOG.debug(e);
			throw e;
		} finally {
			brokerPool.release(broker);
		}
	}

	public String createId(User user, String collName) throws EXistException {
		DBBroker broker = brokerPool.get(user);
		try {
			Collection collection = broker.getCollection(collName);
			if (collection == null)
				throw new EXistException("collection " + collName + " not found!");
			String id;
			Random rand = new Random();
			boolean ok;
			do {
				ok = true;
				id = Integer.toHexString(rand.nextInt()) + ".xml";
				// check if this id does already exist
				if (collection.hasDocument(id))
					ok = false;

				if (collection.hasSubcollection(id))
					ok = false;

			} while (!ok);
			return id;
		} finally {
			brokerPool.release(broker);
		}
	}

	protected Sequence doQuery(
		User user,
		DBBroker broker,
		String xpath,
		DocumentSet docs,
		NodeSet contextSet,
		Hashtable namespaces,
		String baseURI)
		throws Exception {
		if (docs == null)
			docs = broker.getAllDocuments(new DocumentSet());
		StaticContext context = new StaticContext(broker);
		context.setBaseURI(baseURI);
		context.setStaticallyKnownDocuments(docs);
		if (namespaces != null) {
			Map.Entry entry;
			for (Iterator i = namespaces.entrySet().iterator(); i.hasNext();) {
				entry = (Map.Entry) i.next();
				context.declareNamespace(
					(String) entry.getKey(),
					(String) entry.getValue());
			}
		}
		LOG.debug("query = " + xpath);
		XPathLexer2 lexer = new XPathLexer2(new StringReader(xpath));
		XPathParser2 parser = new XPathParser2(lexer, false);
		XPathTreeParser2 treeParser = new XPathTreeParser2(context);

		parser.xpath();
		if (parser.foundErrors()) {
			throw new EXistException(parser.getErrorMessage());
		}

		AST ast = parser.getAST();
		LOG.debug("generated AST: " + ast.toStringTree());

		PathExpr expr = new PathExpr(context);
		treeParser.xpath(ast, expr);
		if (treeParser.foundErrors()) {
			throw new EXistException(treeParser.getErrorMessage());
		}
		LOG.info("compiled: " + expr.pprint());
		long start = System.currentTimeMillis();
		Sequence result = expr.eval(contextSet, null);
		LOG.info("query took " + (System.currentTimeMillis() - start) + "ms.");
		return result;
	}

	public int executeQuery(User user, String xpath, Hashtable namespaces)
		throws Exception {
		long startTime = System.currentTimeMillis();
		LOG.debug("query: " + xpath);
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			Sequence resultValue =
				doQuery(user, broker, xpath, null, null, namespaces, null);
			QueryResult qr =
				new QueryResult(resultValue, (System.currentTimeMillis() - startTime));
			connectionPool.resultSets.put(qr.hashCode(), qr);
			return qr.hashCode();
		} finally {
			brokerPool.release(broker);
		}
	}

	protected String formatErrorMsg(String message) {
		return formatErrorMsg("error", message);
	}

	protected String formatErrorMsg(String type, String message) {
		StringBuffer buf = new StringBuffer();
		buf.append(
			"<exist:result xmlns:exist=\"http://exist.sourceforge.net/NS/exist\" ");
		buf.append("hitCount=\"0\">");
		buf.append('<');
		buf.append(type);
		buf.append('>');
		buf.append(message);
		buf.append("</");
		buf.append(type);
		buf.append("></exist:result>");
		return buf.toString();
	}

	public Hashtable getCollectionDesc(User user, String rootCollection)
		throws Exception {
		DBBroker broker = brokerPool.get(user);
		try {
			if (rootCollection == null)
				rootCollection = "/db";

			Collection collection = broker.getCollection(rootCollection);
			if (collection == null)
				throw new EXistException("collection " + rootCollection + " not found!");
			Hashtable desc = new Hashtable();
			Vector docs = new Vector();
			Vector collections = new Vector();
			if (collection.getPermissions().validate(user, Permission.READ)) {
				DocumentImpl doc;
				Hashtable hash;
				Permission perms;
				for (Iterator i = collection.iterator(); i.hasNext();) {
					doc = (DocumentImpl) i.next();
					perms = doc.getPermissions();
					hash = new Hashtable(4);
					hash.put("name", doc.getFileName());
					hash.put("owner", perms.getOwner());
					hash.put("group", perms.getOwnerGroup());
					hash.put("permissions", new Integer(perms.getPermissions()));
					docs.addElement(hash);
				}
				for (Iterator i = collection.collectionIterator(); i.hasNext();)
					collections.addElement((String) i.next());
			}
			Permission perms = collection.getPermissions();
			desc.put("collections", collections);
			desc.put("documents", docs);
			desc.put("name", collection.getName());
			desc.put("created", Long.toString(collection.getCreationTime()));
			desc.put("owner", perms.getOwner());
			desc.put("group", perms.getOwnerGroup());
			desc.put("permissions", new Integer(perms.getPermissions()));
			return desc;
		} finally {
			brokerPool.release(broker);
		}
	}

	public String getDocument(User user, String name, Hashtable parametri)
		throws Exception {
		long start = System.currentTimeMillis();
		DBBroker broker = null;

		String stylesheet = null;
		String encoding = "UTF-8";
		Hashtable styleparam = null;

		try {
			broker = brokerPool.get(user);
			Configuration config = broker.getConfiguration();
			String option = (String) config.getProperty("serialization.enable-xinclude");
			String prettyPrint = (String) config.getProperty("serialization.indent");

			DocumentImpl doc = (DocumentImpl) broker.getDocument(name);
			if (doc == null) {
				LOG.debug("document " + name + " not found!");
				throw new EXistException("document not found");
			}
			Serializer serializer = broker.getSerializer();

			if (parametri != null) {

				for (Enumeration en = parametri.keys(); en.hasMoreElements();) {

					String param = (String) en.nextElement();
					String paramvalue = parametri.get(param).toString();
					//LOG.debug("-------Parametri passati:"+param+": "+paramvalue); 

					if (param.equals(EXistOutputKeys.EXPAND_XINCLUDES)) {
						option = (paramvalue.equals("yes")) ? "true" : "false";
					}

					if (param.equals(OutputKeys.INDENT)) {
						prettyPrint = paramvalue;
					}

					if (param.equals(OutputKeys.ENCODING)) {
						encoding = paramvalue;
					}

					if (param.equals(EXistOutputKeys.STYLESHEET)) {
						stylesheet = paramvalue;
					}

					if (param.equals(EXistOutputKeys.STYLESHEET_PARAM)) {
						styleparam = (Hashtable) parametri.get(param);
					}

					if (param.equals(OutputKeys.DOCTYPE_SYSTEM)) {
						serializer.setProperty(OutputKeys.DOCTYPE_SYSTEM, paramvalue);
					}

				}

			}

			if (option.equals("true")) {
				serializer.setProperty(EXistOutputKeys.EXPAND_XINCLUDES, "yes");
			} else {
				serializer.setProperty(EXistOutputKeys.EXPAND_XINCLUDES, "no");
			}

			serializer.setProperty(OutputKeys.ENCODING, encoding);
			serializer.setProperty(OutputKeys.INDENT, prettyPrint);
			if (stylesheet != null) {
				
			if (stylesheet.indexOf(":") < 0) {
				if (!stylesheet.startsWith("/")) {
					// make path relative to current collection
					String collection;
					if (doc.getCollection() != null)
						collection = doc.getCollection().getName();
					else {
						int cp = doc.getFileName().lastIndexOf("/");
						collection = (cp > 0) ? doc.getFileName().substring(0, cp) : "/";
					}
					stylesheet =
						(collection.equals("/")
							? '/' + stylesheet
							: collection + '/' + stylesheet);
				}
				
			}
				serializer.setStylesheet(stylesheet);

				// set stylesheet param if presents
				if (styleparam != null) {
					for (Enumeration en1 = styleparam.keys(); en1.hasMoreElements();) {
						String param1 = (String) en1.nextElement();
						String paramvalue1 = styleparam.get(param1).toString();
						// System.out.println("-->"+param1+"--"+paramvalue1);
						serializer.setStylesheetParamameter(param1, paramvalue1);
					}
				}

			}
			String xml = serializer.serialize(doc);

			return xml;
		} catch (NoSuchMethodError nsme) {
			nsme.printStackTrace();
			return null;
		} finally {
			brokerPool.release(broker);
		}
	}

	public int xupdate(User user, String collectionName, String xupdate)
		throws SAXException, PermissionDeniedException, EXistException, XPathException {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			Collection collection = broker.getCollection(collectionName);
			if (collection == null)
				throw new EXistException("collection " + collectionName + " not found");
			DocumentSet docs = collection.allDocs(broker, new DocumentSet(), true);
			XUpdateProcessor processor = new XUpdateProcessor(broker, docs);
			Modification modifications[] =
				processor.parse(new InputSource(new StringReader(xupdate)));
			long mods = 0;
			for (int i = 0; i < modifications.length; i++) {
				mods += modifications[i].process();
				broker.flush();
			}
			return (int) mods;
		} catch (ParserConfigurationException e) {
			throw new EXistException(e.getMessage());
		} catch (IOException e) {
			throw new EXistException(e.getMessage());
		} finally {
			brokerPool.release(broker);
		}
	}

	public int xupdateResource(User user, String resource, String xupdate)
		throws SAXException, PermissionDeniedException, EXistException, XPathException {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			Document doc = broker.getDocument(resource);
			if (doc == null)
				throw new EXistException("document " + resource + " not found");
			DocumentSet docs = new DocumentSet();
			docs.add(doc);
			XUpdateProcessor processor = new XUpdateProcessor(broker, docs);
			Modification modifications[] =
				processor.parse(new InputSource(new StringReader(xupdate)));
			long mods = 0;
			for (int i = 0; i < modifications.length; i++) {
				mods += modifications[i].process();
				broker.flush();
			}
			return (int) mods;
		} catch (ParserConfigurationException e) {
			throw new EXistException(e.getMessage());
		} catch (IOException e) {
			throw new EXistException(e.getMessage());
		} finally {
			brokerPool.release(broker);
		}
	}

	public boolean sync() {
		DBBroker broker = null;
		try {
			broker = brokerPool.get();
			broker.sync();
		} catch (EXistException e) {
		} finally {
			brokerPool.release(broker);
		}
		return true;
	}

	/**
	 *  Gets the documentListing attribute of the RpcConnection object
	 *
	 *@param  user                Description of the Parameter
	 *@return                     The documentListing value
	 *@exception  EXistException  Description of the Exception
	 */
	public Vector getDocumentListing(User user) throws EXistException {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			DocumentSet docs = broker.getAllDocuments(new DocumentSet());
			String names[] = docs.getNames();
			Vector vec = new Vector();
			for (int i = 0; i < names.length; i++)
				vec.addElement(names[i]);

			return vec;
		} finally {
			brokerPool.release(broker);
		}
	}

	/**
	 *  Gets the documentListing attribute of the RpcConnection object
	 *
	 *@param  collection                     Description of the Parameter
	 *@param  user                           Description of the Parameter
	 *@return                                The documentListing value
	 *@exception  EXistException             Description of the Exception
	 *@exception  PermissionDeniedException  Description of the Exception
	 */
	public Vector getDocumentListing(User user, String name)
		throws EXistException, PermissionDeniedException {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			if (!name.startsWith("/"))
				name = '/' + name;
			if (!name.startsWith("/db"))
				name = "/db" + name;
			Collection collection = broker.getCollection(name);
			Vector vec = new Vector();
			if (collection == null)
				return vec;
			String resource;
			int p;
			for (Iterator i = collection.iterator(); i.hasNext();) {
				resource = ((DocumentImpl) i.next()).getFileName();
				p = resource.lastIndexOf('/');
				vec.addElement(p < 0 ? resource : resource.substring(p + 1));
			}
			return vec;
		} finally {
			brokerPool.release(broker);
		}
	}

	public Hashtable listDocumentPermissions(User user, String name)
		throws EXistException, PermissionDeniedException {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			if (!name.startsWith("/"))
				name = '/' + name;
			if (!name.startsWith("/db"))
				name = "/db" + name;
			Collection collection = broker.getCollection(name);
			if (!collection.getPermissions().validate(user, Permission.READ))
				throw new PermissionDeniedException(
					"not allowed to read collection " + name);
			Hashtable result = new Hashtable(collection.getDocumentCount());
			if (collection == null)
				return result;
			DocumentImpl doc;
			Permission perm;
			Vector tmp;
			String docName;
			for (Iterator i = collection.iterator(); i.hasNext();) {
				doc = (DocumentImpl) i.next();
				perm = doc.getPermissions();
				tmp = new Vector(3);
				tmp.addElement(perm.getOwner());
				tmp.addElement(perm.getOwnerGroup());
				tmp.addElement(new Integer(perm.getPermissions()));
				docName =
					doc.getFileName().substring(doc.getFileName().lastIndexOf('/') + 1);
				result.put(docName, tmp);
			}
			return result;
		} finally {
			brokerPool.release(broker);
		}
	}

	public Hashtable listCollectionPermissions(User user, String name)
		throws EXistException, PermissionDeniedException {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			if (!name.startsWith("/"))
				name = '/' + name;
			if (!name.startsWith("/db"))
				name = "/db" + name;
			Collection collection = broker.getCollection(name);
			if (!collection.getPermissions().validate(user, Permission.READ))
				throw new PermissionDeniedException(
					"not allowed to read collection " + name);
			Hashtable result = new Hashtable(collection.getChildCollectionCount());
			if (collection == null)
				return result;
			String child, path;
			Collection childColl;
			Permission perm;
			Vector tmp;
			for (Iterator i = collection.collectionIterator(); i.hasNext();) {
				child = (String) i.next();
				path = name + '/' + child;
				childColl = broker.getCollection(path);
				perm = childColl.getPermissions();
				tmp = new Vector(3);
				tmp.addElement(perm.getOwner());
				tmp.addElement(perm.getOwnerGroup());
				tmp.addElement(new Integer(perm.getPermissions()));
				result.put(child, tmp);
			}
			return result;
		} finally {
			brokerPool.release(broker);
		}
	}

	/**
	 *  Gets the hits attribute of the RpcConnection object
	 *
	 *@param  resultId            Description of the Parameter
	 *@param  user                Description of the Parameter
	 *@return                     The hits value
	 *@exception  EXistException  Description of the Exception
	 */
	public int getHits(User user, int resultId) throws EXistException {
		QueryResult qr = (QueryResult) connectionPool.resultSets.get(resultId);
		if (qr == null)
			throw new EXistException("result set unknown or timed out");
		qr.timestamp = System.currentTimeMillis();
		if (qr.result == null)
			return 0;
		return qr.result.getLength();
	}

	/**
	 *  Get permissions for the given collection or resource
	 *
	 *@param  name                           Description of the Parameter
	 *@param  user                           Description of the Parameter
	 *@return                                The permissions value
	 *@exception  EXistException             Description of the Exception
	 *@exception  PermissionDeniedException  Description of the Exception
	 */
	public Hashtable getPermissions(User user, String name)
		throws EXistException, PermissionDeniedException {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			if (!name.startsWith("/"))
				name = '/' + name;
			if (!name.startsWith("/db"))
				name = "/db" + name;
			Collection collection = broker.getCollection(name);
			Permission perm = null;
			if (collection == null) {
				DocumentImpl doc = (DocumentImpl) broker.getDocument(name);
				if (doc == null)
					throw new EXistException(
						"document or collection " + name + " not found");
				perm = doc.getPermissions();
			} else {
				perm = collection.getPermissions();
			}
			Hashtable result = new Hashtable();
			result.put("owner", perm.getOwner());
			result.put("group", perm.getOwnerGroup());
			result.put("permissions", new Integer(perm.getPermissions()));
			return result;
		} finally {
			brokerPool.release(broker);
		}
	}

	public Date getCreationDate(User user, String collectionPath)
		throws PermissionDeniedException, EXistException {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			if (!collectionPath.startsWith("/"))
				collectionPath = '/' + collectionPath;
			if (!collectionPath.startsWith("/db"))
				collectionPath = "/db" + collectionPath;
			Collection collection = broker.getCollection(collectionPath);
			if (collection == null)
				throw new EXistException("collection " + collectionPath + " not found");
			return new Date(collection.getCreationTime());
		} finally {
			brokerPool.release(broker);
		}
	}

	public Vector getTimestamps(User user, String documentPath)
		throws PermissionDeniedException, EXistException {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			if (!documentPath.startsWith("/"))
				documentPath = '/' + documentPath;
			if (!documentPath.startsWith("/db"))
				documentPath = "/db" + documentPath;
			DocumentImpl doc = (DocumentImpl) broker.getDocument(documentPath);
			if (doc == null) {
				LOG.debug("document " + documentPath + " not found!");
				throw new EXistException("document not found");
			}
			Vector vector = new Vector(2);
			vector.addElement(new Date(doc.getCreated()));
			vector.addElement(new Date(doc.getLastModified()));
			return vector;
		} finally {
			brokerPool.release(broker);
		}
	}

	/**
	 *  Gets the permissions attribute of the RpcConnection object
	 *
	 *@param  user                           Description of the Parameter
	 *@param  name                           Description of the Parameter
	 *@return                                The permissions value
	 *@exception  EXistException             Description of the Exception
	 *@exception  PermissionDeniedException  Description of the Exception
	 */
	public Hashtable getUser(User user, String name)
		throws EXistException, PermissionDeniedException {
		User u = brokerPool.getSecurityManager().getUser(name);
		if (u == null)
			throw new EXistException("user " + name + " does not exist");
		Hashtable tab = new Hashtable();
		tab.put("name", u.getName());
		Vector groups = new Vector();
		for (Iterator i = u.getGroups(); i.hasNext();)
			groups.addElement(i.next());
		tab.put("groups", groups);
		if (u.getHome() != null)
			tab.put("home", u.getHome());
		return tab;
	}

	public Vector getUsers(User user) throws EXistException, PermissionDeniedException {
		User users[] = brokerPool.getSecurityManager().getUsers();
		Vector r = new Vector();
		for (int i = 0; i < users.length; i++) {
			final Hashtable tab = new Hashtable();
			tab.put("name", users[i].getName());
			Vector groups = new Vector();
			for (Iterator j = users[i].getGroups(); j.hasNext();)
				groups.addElement(j.next());
			tab.put("groups", groups);
			if (users[i].getHome() != null)
				tab.put("home", users[i].getHome());
			r.addElement(tab);
		}
		return r;
	}

	public Vector getGroups(User user) throws EXistException, PermissionDeniedException {
		String[] groups = brokerPool.getSecurityManager().getGroups();
		Vector v = new Vector(groups.length);
		for (int i = 0; i < groups.length; i++) {
			v.addElement(groups[i]);
		}
		return v;
	}

	public boolean hasDocument(User user, String name) throws Exception {
		DBBroker broker = brokerPool.get(user);
		boolean r = (broker.getDocument(name) != null);
		brokerPool.release(broker);
		return r;
	}

	public boolean parse(User user, byte[] xml, String docName, boolean replace)
		throws Exception {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			int p = docName.lastIndexOf('/');
			if (p < 0 || p == docName.length() - 1)
				throw new EXistException("Illegal document path");
			String collectionName = docName.substring(0, p);
			docName = docName.substring(p + 1);
			Collection collection = broker.getCollection(collectionName);
			if (collection == null)
				throw new EXistException("Collection " + collectionName + " not found");
			long startTime = System.currentTimeMillis();
			DocumentImpl doc =
				collection.addDocument(
					broker,
					docName,
					new InputSource(new ByteArrayInputStream(xml)));
			LOG.debug(
				"parsing "
					+ docName
					+ " took "
					+ (System.currentTimeMillis() - startTime)
					+ "ms.");
			return doc != null;
		} catch (Exception e) {
			LOG.debug(e.getMessage(), e);
			throw e;
		} finally {
			brokerPool.release(broker);
		}
	}

	/**
	 * Parse a file previously uploaded with upload.
	 * 
	 * The temporary file will be removed.
	 * 
	 * @param user
	 * @param localFile
	 * @throws EXistException
	 * @throws IOException
	 */
	public boolean parseLocal(
		User user,
		String localFile,
		String docName,
		boolean replace)
		throws EXistException, PermissionDeniedException, SAXException, TriggerException {
		File file = new File(localFile);
		if (!file.canRead())
			throw new EXistException("unable to read file " + localFile);
		DBBroker broker = null;
		DocumentImpl doc = null;
		try {
			broker = brokerPool.get(user);
			int p = docName.lastIndexOf('/');
			if (p < 0 || p == docName.length() - 1)
				throw new EXistException("Illegal document path");
			String collectionName = docName.substring(0, p);
			docName = docName.substring(p + 1);
			Collection collection = broker.getCollection(collectionName);
			if (collection == null)
				throw new EXistException("Collection " + collectionName + " not found");
			String uri = file.toURI().toASCIIString();
			collection.addDocument(
				broker,
				docName,
				new InputSource(uri));
		} finally {
			brokerPool.release(broker);
		}
		file.delete();
		return doc != null;
	}

	public String upload(User user, byte[] chunk, int length, String fileName)
		throws EXistException, IOException {
		File file;
		if (fileName == null || fileName.length() == 0) {
			// create temporary file
			file = File.createTempFile("rpc", "xml");
			fileName = file.getAbsolutePath();
			LOG.debug("created temporary file " + file.getAbsolutePath());
		} else {
			LOG.debug("appending to file " + fileName);
			file = new File(fileName);
		}
		if (!file.canWrite())
			throw new EXistException("cannot write to file " + fileName);
		FileOutputStream os = new FileOutputStream(file, true);
		os.write(chunk, 0, length);
		os.close();
		return fileName;
	}

	protected String printAll(
		DBBroker broker,
		NodeList resultSet,
		int howmany,
		int start,
		boolean prettyPrint,
		long queryTime)
		throws Exception {
		if (resultSet.getLength() == 0)
			return "<?xml version=\"1.0\"?>\n"
				+ "<exist:result xmlns:exist=\"http://exist.sourceforge.net/NS/exist\" "
				+ "hitCount=\"0\"/>";
		Node n;
		Node nn;
		Element temp;
		DocumentImpl owner;
		if (howmany > resultSet.getLength() || howmany == 0)
			howmany = resultSet.getLength();

		if (start < 1 || start > resultSet.getLength())
			throw new EXistException("start parameter out of range");
		Serializer serializer = broker.getSerializer();
		serializer.setProperty(OutputKeys.INDENT, prettyPrint ? "yes" : "no");
		return serializer.serialize((NodeSet) resultSet, start, howmany, queryTime);
	}

	protected String printValues(
		Sequence result,
		int howmany,
		int start,
		boolean prettyPrint)
		throws Exception {
		if (result.getLength() == 0)
			return "<?xml version=\"1.0\"?>\n"
				+ "<exist:result xmlns:exist=\"http://exist.sourceforge.net/NS/exist\" "
				+ "hitCount=\"0\"/>";
		if (howmany > result.getLength() || howmany == 0)
			howmany = result.getLength();

		if (start < 1 || start > result.getLength())
			throw new EXistException("start parameter out of range");
		Document dest = docBuilder.newDocument();
		Element root =
			dest.createElementNS("http://exist.sourceforge.net/NS/exist", "exist:result");
		root.setAttribute("xmlns:exist", "http://exist.sourceforge.net/NS/exist");
		root.setAttribute("hitCount", Integer.toString(result.getLength()));
		dest.appendChild(root);

		Element temp;
		Item item;
		for (int i = start - 1; i < start + howmany - 1; i++) {
			item = result.itemAt(i);
			switch (item.getType()) {
				case Type.NUMBER :
					temp =
						dest.createElementNS(
							"http://exist.sourceforge.net/NS/exist",
							"exist:number");
					break;
				case Type.STRING :
					temp =
						dest.createElementNS(
							"http://exist.sourceforge.net/NS/exist",
							"exist:string");
					break;
				case Type.BOOLEAN :
					temp =
						dest.createElementNS(
							"http://exist.sourceforge.net/NS/exist",
							"exist:boolean");
					break;
				default :
					LOG.debug("unknown type: " + item.getType());
					continue;
			}
			temp.appendChild(dest.createTextNode(item.getStringValue()));
			root.appendChild(temp);
		}
		StringWriter sout = new StringWriter();
		Properties props = new Properties();
		props.setProperty(OutputKeys.INDENT, prettyPrint ? "yes" : "no");
		props.setProperty(OutputKeys.ENCODING, "UTF-8");
		DOMSerializer serializer = new DOMSerializer(sout, props);
		try {
			serializer.serialize(dest);
		} catch (TransformerException ioe) {
			LOG.warn(ioe);
			throw ioe;
		}
		return sout.toString();
	}

	public String query(
		User user,
		String xpath,
		int howmany,
		int start,
		boolean prettyPrint,
		boolean summary,
		Hashtable namespaces)
		throws Exception {
		return query(user, xpath, howmany, start, prettyPrint, summary, namespaces, null);
	}

	public String query(
		User user,
		String xpath,
		int howmany,
		int start,
		boolean prettyPrint,
		boolean summary,
		Hashtable namespaces,
		String sortExpr)
		throws Exception {
		long startTime = System.currentTimeMillis();
		String result;
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			Sequence resultSeq =
				doQuery(user, broker, xpath, null, null, namespaces, null);
			if (resultSeq == null)
				return "<?xml version=\"1.0\"?>\n"
					+ "<exist:result xmlns:exist=\"http://exist.sourceforge.net/NS/exist\" "
					+ "hitCount=\"0\"/>";

			switch (resultSeq.getItemType()) {
				case Type.NODE :
					NodeSet resultSet = (NodeSet) resultSeq;
					if (sortExpr != null) {
						SortedNodeSet sorted =
							new SortedNodeSet(brokerPool, user, sortExpr);
						sorted.addAll(resultSet);
						resultSet = sorted;
					}
					result =
						printAll(
							broker,
							resultSet,
							howmany,
							start,
							prettyPrint,
							(System.currentTimeMillis() - startTime));
					break;
				default :
					result = printValues(resultSeq, howmany, start, prettyPrint);
					break;
			}
		} finally {
			brokerPool.release(broker);
		}
		return result;
	}

	public Vector query(User user, String xpath) throws Exception {
		return query(user, xpath, null, null);
	}

	public Vector query(User user, String xpath, String docName, String s_id)
		throws Exception {
		long startTime = System.currentTimeMillis();
		Vector result = new Vector();
		NodeSet nodes = null;
		DocumentSet docs = null;
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			if (docName != null && s_id != null) {
				long id = Long.parseLong(s_id);
				DocumentImpl doc;
				if (!documentCache.containsKey(docName)) {
					doc = (DocumentImpl) broker.getDocument(docName);
					documentCache.put(docName, doc);
				} else
					doc = (DocumentImpl) documentCache.get(docName);
				NodeProxy node = new NodeProxy(doc, id);
				nodes = new ArraySet(1);
				nodes.add(node);
				docs = new DocumentSet();
				docs.add(node.doc);
			}
			Sequence resultSeq = doQuery(user, broker, xpath, docs, nodes, null, null);
			if (resultSeq == null)
				return result;
			switch (resultSeq.getItemType()) {
				case Type.NODE :
					NodeList resultSet = (NodeList) resultSeq;
					NodeProxy p;
					Vector entry;
					for (Iterator i = ((NodeSet) resultSet).iterator(); i.hasNext();) {
						p = (NodeProxy) i.next();
						entry = new Vector();
						entry.addElement(p.doc.getFileName());
						entry.addElement(Long.toString(p.getGID()));
						result.addElement(entry);
					}
					break;
				default :
					Item item;
					for (int i = 0; i < resultSeq.getLength(); i++) {
						item = resultSeq.itemAt(i);
						result.addElement(item.getStringValue());
					}
			}
		} finally {
			brokerPool.release(broker);
		}
		return result;
	}

	public Hashtable queryP(
		User user,
		String xpath,
		String docName,
		String s_id,
		Hashtable parameters)
		throws Exception {
		long startTime = System.currentTimeMillis();
		String sortBy = (String) parameters.get(RpcAPI.SORT_EXPR);
		String baseURI = (String) parameters.get(RpcAPI.BASE_URI);
		Hashtable namespaces = (Hashtable) parameters.get(RpcAPI.NAMESPACES);

		Hashtable ret = new Hashtable();
		Vector result = new Vector();
		NodeSet nodes = null;
		DocumentSet docs = null;
		Sequence resultSeq = null;
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			if (docName != null && s_id != null) {
				long id = Long.parseLong(s_id);
				DocumentImpl doc;
				if (!documentCache.containsKey(docName)) {
					doc = (DocumentImpl) broker.getDocument(docName);
					documentCache.put(docName, doc);
				} else
					doc = (DocumentImpl) documentCache.get(docName);
				NodeProxy node = new NodeProxy(doc, id);
				nodes = new ArraySet(1);
				nodes.add(node);
				docs = new DocumentSet();
				docs.add(node.doc);
			}
			resultSeq = doQuery(user, broker, xpath, docs, nodes, namespaces, baseURI);
			if (resultSeq == null)
				return ret;
			LOG.debug("found " + resultSeq.getLength());
			if (sortBy != null) {
				SortedNodeSet sorted = new SortedNodeSet(brokerPool, user, sortBy);
				sorted.addAll(resultSeq);
				resultSeq = sorted;
			}
			NodeProxy p;
			Vector entry;
			if (resultSeq != null) {
				SequenceIterator i = resultSeq.iterate();
				if (i != null) {
					Item next;
					while (i.hasNext()) {
						next = i.nextItem();
						if (Type.subTypeOf(next.getType(), Type.NODE)) {
							entry = new Vector();
							if (((NodeValue)next).getImplementationType() == NodeValue.PERSISTENT_NODE) {
								p = (NodeProxy) next;
								entry.addElement(p.doc.getFileName());
								entry.addElement(Long.toString(p.getGID()));
							} else {
								entry.addElement("temp_xquery/" + next.hashCode());
								entry.addElement(
									String.valueOf(((NodeImpl) next).getNodeNumber()));
							}
							result.addElement(entry);
						} else
							result.addElement(next.getStringValue());
					}
				} else {
					LOG.debug("sequence iterator is null. Should not");
				}
			} else
				LOG.debug("result sequence is null. Skipping it...");
		} finally {
			brokerPool.release(broker);
		}
		QueryResult qr =
			new QueryResult(resultSeq, (System.currentTimeMillis() - startTime));
		connectionPool.resultSets.put(qr.hashCode(), qr);
		ret.put("id", new Integer(qr.hashCode()));
		ret.put("results", result);
		return ret;
	}

	public void releaseQueryResult(int handle) {
		connectionPool.resultSets.remove(handle);
		LOG.debug("removed query result with handle " + handle);
	}

	public void remove(User user, String docName) throws Exception {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			int p = docName.lastIndexOf('/');
			if (p < 0 || p == docName.length() - 1)
				throw new EXistException("Illegal document path");
			String collectionName = docName.substring(0, p);
			docName = docName.substring(p + 1);
			Collection collection = broker.getCollection(collectionName);
			if (collection == null)
				throw new EXistException("Collection " + collectionName + " not found");
			collection.removeDocument(broker, docName);
		} finally {
			brokerPool.release(broker);
		}
	}

	public boolean removeCollection(User user, String name) throws Exception {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			if (broker.getCollection(name) == null)
				return false;
			LOG.debug("removing collection " + name);
			if (parser != null)
				parser.collection = null;
			return broker.removeCollection(name);
		} finally {
			brokerPool.release(broker);
		}
	}

	public boolean removeUser(User user, String name)
		throws EXistException, PermissionDeniedException {
		org.exist.security.SecurityManager manager = brokerPool.getSecurityManager();
		if (!manager.hasAdminPrivileges(user))
			throw new PermissionDeniedException("you are not allowed to remove users");

		manager.deleteUser(name);
		return true;
	}

	public String retrieve(
		User user,
		String docName,
		String s_id,
		boolean prettyPrint,
		String encoding)
		throws Exception {
		DBBroker broker = brokerPool.get(user);
		try {
			long id = Long.parseLong(s_id);
			DocumentImpl doc;
			if (!documentCache.containsKey(docName)) {
				doc = (DocumentImpl) broker.getDocument(docName);
				documentCache.put(docName, doc);
			} else
				doc = (DocumentImpl) documentCache.get(docName);

			NodeProxy node = new NodeProxy(doc, id);
			Serializer serializer = broker.getSerializer();
			serializer.setProperty(OutputKeys.ENCODING, encoding);
			serializer.setProperty(OutputKeys.INDENT, prettyPrint ? "yes" : "no");
			return serializer.serialize(node);
		} finally {
			brokerPool.release(broker);
		}
	}

	public String retrieve(
		User user,
		int resultId,
		int num,
		boolean prettyPrint,
		String encoding)
		throws Exception {
		DBBroker broker = brokerPool.get(user);
		try {
			QueryResult qr = (QueryResult) connectionPool.resultSets.get(resultId);
			if (qr == null)
				throw new EXistException("result set unknown or timed out");
			qr.timestamp = System.currentTimeMillis();
			Item item = qr.result.itemAt(num);
			if (item == null)
				throw new EXistException("index out of range");
			if (item instanceof NodeProxy) {
				NodeProxy proxy = (NodeProxy) item;
				Serializer serializer = broker.getSerializer();
				serializer.setProperty(OutputKeys.ENCODING, encoding);
				serializer.setProperty(OutputKeys.INDENT, prettyPrint ? "yes" : "no");
				return serializer.serialize(proxy);
			} else if (item instanceof Node) {
				StringWriter writer = new StringWriter();
				Properties props = new Properties();
				props.setProperty(OutputKeys.ENCODING, encoding);
				props.setProperty(OutputKeys.INDENT, prettyPrint ? "yes" : "no");
				DOMSerializer serializer = new DOMSerializer(writer, props);
				serializer.serialize((Node) item);
				return writer.toString();
			} else {
				return item.getStringValue();
			}
		} finally {
			brokerPool.release(broker);
		}
	}

	public void run() {
		synchronized (this) {
			while (!terminate)
				try {
					this.wait(500);
				} catch (InterruptedException inte) {
				}

		}
		// broker.shutdown();
	}

	/**
	 *  Sets the permissions attribute of the RpcConnection object
	 *
	 *@param  user                           The new permissions value
	 *@param  resource                       The new permissions value
	 *@param  permissions                    The new permissions value
	 *@param  owner                          The new permissions value
	 *@param  ownerGroup                     The new permissions value
	 *@return                                Description of the Return Value
	 *@exception  EXistException             Description of the Exception
	 *@exception  PermissionDeniedException  Description of the Exception
	 */
	public boolean setPermissions(
		User user,
		String resource,
		String owner,
		String ownerGroup,
		String permissions)
		throws EXistException, PermissionDeniedException {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			org.exist.security.SecurityManager manager = brokerPool.getSecurityManager();
			Collection collection = broker.getCollection(resource);
			if (collection == null) {
				DocumentImpl doc = (DocumentImpl) broker.getDocument(resource);
				if (doc == null)
					throw new EXistException(
						"document or collection " + resource + " not found");
				LOG.debug("changing permissions on document " + resource);
				Permission perm = doc.getPermissions();
				if (perm.getOwner().equals(user.getName())
					|| manager.hasAdminPrivileges(user)) {
					if (owner != null) {
						perm.setOwner(owner);
						perm.setGroup(ownerGroup);
					}
					if (permissions != null && permissions.length() > 0)
						perm.setPermissions(permissions);
					broker.saveCollection(doc.getCollection());
					broker.flush();
					return true;
				} else
					throw new PermissionDeniedException("not allowed to change permissions");
			} else {
				LOG.debug("changing permissions on collection " + resource);
				Permission perm = collection.getPermissions();
				if (perm.getOwner().equals(user.getName())
					|| manager.hasAdminPrivileges(user)) {
					if (permissions != null)
						perm.setPermissions(permissions);
					if (owner != null) {
						perm.setOwner(owner);
						perm.setGroup(ownerGroup);
					}
					broker.saveCollection(collection);
					broker.flush();
					return true;
				} else
					throw new PermissionDeniedException("not allowed to change permissions");
			}
		} catch (SyntaxException e) {
			throw new EXistException(e.getMessage());
		} catch (PermissionDeniedException e) {
			throw new EXistException(e.getMessage());
		} finally {
			brokerPool.release(broker);
		}
	}

	public boolean setPermissions(
		User user,
		String resource,
		String owner,
		String ownerGroup,
		int permissions)
		throws EXistException, PermissionDeniedException {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			org.exist.security.SecurityManager manager = brokerPool.getSecurityManager();
			Collection collection = broker.getCollection(resource);
			if (collection == null) {
				DocumentImpl doc = (DocumentImpl) broker.getDocument(resource);
				if (doc == null)
					throw new EXistException(
						"document or collection " + resource + " not found");
				LOG.debug("changing permissions on document " + resource);
				Permission perm = doc.getPermissions();
				if (perm.getOwner().equals(user.getName())
					|| manager.hasAdminPrivileges(user)) {
					if (owner != null) {
						perm.setOwner(owner);
						perm.setGroup(ownerGroup);
					}
					perm.setPermissions(permissions);
					broker.saveCollection(doc.getCollection());
					broker.flush();
					return true;
				} else
					throw new PermissionDeniedException("not allowed to change permissions");
			} else {
				LOG.debug("changing permissions on collection " + resource);
				Permission perm = collection.getPermissions();
				if (perm.getOwner().equals(user.getName())
					|| manager.hasAdminPrivileges(user)) {
					perm.setPermissions(permissions);
					if (owner != null) {
						perm.setOwner(owner);
						perm.setGroup(ownerGroup);
					}
					broker.saveCollection(collection);
					broker.flush();
					return true;
				} else
					throw new PermissionDeniedException("not allowed to change permissions");
			}
		} catch (PermissionDeniedException e) {
			throw new EXistException(e.getMessage());
		} finally {
			brokerPool.release(broker);
		}
	}

	/**
	 *  Sets the password attribute of the RpcConnection object
	 *
	 *@param  user                           The new password value
	 *@param  name                           The new password value
	 *@param  passwd                         The new password value
	 *@param  groups                         The new user value
	 *@return                                Description of the Return Value
	 *@exception  EXistException             Description of the Exception
	 *@exception  PermissionDeniedException  Description of the Exception
	 */
	public boolean setUser(
		User user,
		String name,
		String passwd,
		Vector groups,
		String home)
		throws EXistException, PermissionDeniedException {
		org.exist.security.SecurityManager manager = brokerPool.getSecurityManager();
		User u;
		if (!manager.hasUser(name)) {
			if (!manager.hasAdminPrivileges(user))
				throw new PermissionDeniedException("not allowed to create user");
			u = new User(name);
			u.setPasswordDigest(passwd);
		} else {
			u = manager.getUser(name);
			if (!(u.getName().equals(user.getName())
				|| manager.hasAdminPrivileges(user)))
				throw new PermissionDeniedException("you are not allowed to change this user");
			u.setPasswordDigest(passwd);
		}
		String g;
		for (Iterator i = groups.iterator(); i.hasNext();) {
			g = (String) i.next();
			if (!u.hasGroup(g))
				u.addGroup(g);
		}
		if (home != null)
			u.setHome(home);
		manager.setUser(u);
		return true;
	}

	/**
	 *  Description of the Method
	 *
	 *@param  xpath          Description of the Parameter
	 *@param  user           Description of the Parameter
	 *@return                Description of the Return Value
	 *@exception  Exception  Description of the Exception
	 */
	public Hashtable summary(User user, String xpath) throws Exception {
		long startTime = System.currentTimeMillis();
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			Sequence resultSeq = doQuery(user, broker, xpath, null, null, null, null);
			if (resultSeq == null)
				return new Hashtable();
			NodeList resultSet = (NodeList) resultSeq;
			HashMap map = new HashMap();
			HashMap doctypes = new HashMap();
			NodeProxy p;
			String docName;
			DocumentType doctype;
			NodeCount counter;
			DoctypeCount doctypeCounter;
			for (Iterator i = ((NodeSet) resultSet).iterator(); i.hasNext();) {
				p = (NodeProxy) i.next();
				docName = p.doc.getFileName();
				doctype = p.doc.getDoctype();
				if (map.containsKey(docName)) {
					counter = (NodeCount) map.get(docName);
					counter.inc();
				} else {
					counter = new NodeCount(p.doc);
					map.put(docName, counter);
				}
				if (doctype == null)
					continue;
				if (doctypes.containsKey(doctype.getName())) {
					doctypeCounter = (DoctypeCount) doctypes.get(doctype.getName());
					doctypeCounter.inc();
				} else {
					doctypeCounter = new DoctypeCount(doctype);
					doctypes.put(doctype.getName(), doctypeCounter);
				}
			}
			Hashtable result = new Hashtable();
			result.put(
				"queryTime",
				new Integer((int) (System.currentTimeMillis() - startTime)));
			result.put("hits", new Integer(resultSet.getLength()));
			Vector documents = new Vector();
			Vector hitsByDoc;
			for (Iterator i = map.values().iterator(); i.hasNext();) {
				counter = (NodeCount) i.next();
				hitsByDoc = new Vector();
				hitsByDoc.addElement(counter.doc.getFileName());
				hitsByDoc.addElement(new Integer(counter.doc.getDocId()));
				hitsByDoc.addElement(new Integer(counter.count));
				documents.addElement(hitsByDoc);
			}
			result.put("documents", documents);
			Vector dtypes = new Vector();
			Vector hitsByType;
			DoctypeCount docTemp;
			for (Iterator i = doctypes.values().iterator(); i.hasNext();) {
				docTemp = (DoctypeCount) i.next();
				hitsByType = new Vector();
				hitsByType.addElement(docTemp.doctype.getName());
				hitsByType.addElement(new Integer(docTemp.count));
				dtypes.addElement(hitsByType);
			}
			result.put("doctypes", dtypes);
			return result;
		} finally {
			brokerPool.release(broker);
		}
	}

	/**
	 *  Description of the Method
	 *
	 *@param  resultId            Description of the Parameter
	 *@param  user                Description of the Parameter
	 *@return                     Description of the Return Value
	 *@exception  EXistException  Description of the Exception
	 */
	public Hashtable summary(User user, int resultId) throws EXistException {
		long startTime = System.currentTimeMillis();
		QueryResult qr = (QueryResult) connectionPool.resultSets.get(resultId);
		if (qr == null)
			throw new EXistException("result set unknown or timed out");
		qr.timestamp = System.currentTimeMillis();
		Hashtable result = new Hashtable();
		result.put("queryTime", new Integer((int) qr.queryTime));
		if (qr.result == null) {
			result.put("hits", new Integer(0));
			return result;
		}
		DBBroker broker = brokerPool.get(user);
		try {
			NodeList resultSet = (NodeList) qr.result;
			HashMap map = new HashMap();
			HashMap doctypes = new HashMap();
			NodeProxy p;
			String docName;
			DocumentType doctype;
			NodeCount counter;
			DoctypeCount doctypeCounter;
			for (Iterator i = ((NodeSet) resultSet).iterator(); i.hasNext();) {
				p = (NodeProxy) i.next();
				docName = p.doc.getFileName();
				doctype = p.doc.getDoctype();
				if (map.containsKey(docName)) {
					counter = (NodeCount) map.get(docName);
					counter.inc();
				} else {
					counter = new NodeCount(p.doc);
					map.put(docName, counter);
				}
				if (doctype == null)
					continue;
				if (doctypes.containsKey(doctype.getName())) {
					doctypeCounter = (DoctypeCount) doctypes.get(doctype.getName());
					doctypeCounter.inc();
				} else {
					doctypeCounter = new DoctypeCount(doctype);
					doctypes.put(doctype.getName(), doctypeCounter);
				}
			}
			result.put("hits", new Integer(resultSet.getLength()));
			Vector documents = new Vector();
			Vector hitsByDoc;
			for (Iterator i = map.values().iterator(); i.hasNext();) {
				counter = (NodeCount) i.next();
				hitsByDoc = new Vector();
				hitsByDoc.addElement(counter.doc.getFileName());
				hitsByDoc.addElement(new Integer(counter.doc.getDocId()));
				hitsByDoc.addElement(new Integer(counter.count));
				documents.addElement(hitsByDoc);
			}
			result.put("documents", documents);
			Vector dtypes = new Vector();
			Vector hitsByType;
			DoctypeCount docTemp;
			for (Iterator i = doctypes.values().iterator(); i.hasNext();) {
				docTemp = (DoctypeCount) i.next();
				hitsByType = new Vector();
				hitsByType.addElement(docTemp.doctype.getName());
				hitsByType.addElement(new Integer(docTemp.count));
				dtypes.addElement(hitsByType);
			}
			result.put("doctypes", dtypes);
			return result;
		} finally {
			brokerPool.release(broker);
		}
	}

	public Vector getIndexedElements(User user, String collectionName, boolean inclusive)
		throws EXistException, PermissionDeniedException {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			Collection collection = broker.getCollection(collectionName);
			if (collection == null)
				throw new EXistException("collection " + collectionName + " not found");
			Occurrences occurrences[] = broker.scanIndexedElements(collection, inclusive);
			Vector result = new Vector(occurrences.length);
			Vector temp;
			for (int i = 0; i < occurrences.length; i++) {
				temp = new Vector(2);
				temp.addElement(occurrences[i].getTerm());
				temp.addElement(new Integer(occurrences[i].getOccurrences()));
				result.addElement(temp);
			}
			return result;
		} finally {
			brokerPool.release(broker);
		}
	}

	public Vector scanIndexTerms(
		User user,
		String collectionName,
		String start,
		String end,
		boolean inclusive)
		throws PermissionDeniedException, EXistException {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			Collection collection = broker.getCollection(collectionName);
			if (collection == null)
				throw new EXistException("collection " + collectionName + " not found");
			Occurrences occurrences[] =
				broker.getTextEngine().scanIndexTerms(
					user,
					collection,
					start,
					end,
					inclusive);
			Vector result = new Vector(occurrences.length);
			Vector temp;
			for (int i = 0; i < occurrences.length; i++) {
				temp = new Vector(2);
				temp.addElement(occurrences[i].getTerm());
				temp.addElement(new Integer(occurrences[i].getOccurrences()));
				result.addElement(temp);
			}
			return result;
		} finally {
			brokerPool.release(broker);
		}
	}

	public void synchronize() {
		documentCache.clear();
	}

	public void terminate() {
		terminate = true;
	}

	/**
	 *  Description of the Class
	 *
	 *@author     wolf
	 *@created    28. Mai 2002
	 */
	class DoctypeCount {
		int count = 1;
		DocumentType doctype;

		/**
		 *  Constructor for the DoctypeCount object
		 *
		 *@param  doctype  Description of the Parameter
		 */
		public DoctypeCount(DocumentType doctype) {
			this.doctype = doctype;
		}

		public void inc() {
			count++;
		}
	}

	class NodeCount {
		int count = 1;
		DocumentImpl doc;

		/**
		 *  Constructor for the NodeCount object
		 *
		 *@param  doc  Description of the Parameter
		 */
		public NodeCount(DocumentImpl doc) {
			this.doc = doc;
		}

		public void inc() {
			count++;
		}
	}

}
