/*
 * Created on 17.03.2005 - $Id$
 */
package org.exist.xquery.test;

import junit.framework.TestCase;
import junit.textui.TestRunner;

import org.exist.xmldb.DatabaseInstanceManager;
import org.exist.xquery.XPathException;
import org.xmldb.api.DatabaseManager;
import org.xmldb.api.base.Collection;
import org.xmldb.api.base.Database;
import org.xmldb.api.base.ResourceSet;
import org.xmldb.api.base.XMLDBException;
import org.xmldb.api.modules.XPathQueryService;

/** Tests for various standart XQuery functions
 * @author jens
 */
public class XQueryFunctionsTest extends TestCase {

	private String[] testvalues;
	private String[] resultvalues;
	
	private XPathQueryService service;
	private Collection root = null;
	private Database database = null;
	
	public static void main(String[] args) throws XPathException {
		TestRunner.run(XQueryFunctionsTest.class);
	}
	
	/**
	 * Constructor for XQueryFunctionsTest.
	 * @param arg0
	 */
	public XQueryFunctionsTest(String arg0) {
		super(arg0);
	}
	
	/** Tests the XQuery-/XPath-function fn:round-half-to-even
	 * with the rounding value typed xs:integer
	 */
	public void testRoundHtE_INTEGER() throws XPathException {
		ResourceSet result 		= null;
		String 		query		= null;
		String		r			= "";
		try {
			
			query 	= "fn:round-half-to-even( xs:integer('1'), 0 )";
			result 	= service.query( query );
			r 		= (String) result.getResource(0).getContent();
			assertEquals( "1", r );
			
			query 	= "fn:round-half-to-even( xs:integer('6'), -1 )";
			result 	= service.query( query );
			r 		= (String) result.getResource(0).getContent();
			assertEquals( "10", r );

			query 	= "fn:round-half-to-even( xs:integer('5'), -1 )";
			result 	= service.query( query );
			r 		= (String) result.getResource(0).getContent();
			assertEquals( "0", r );
		
		} catch (XMLDBException e) {
			System.out.println("testRoundHtE_INTEGER(): "+e);
			fail(e.getMessage());
		}
	}
	
	/** Tests the XQuery-/XPath-function fn:round-half-to-even
	 * with the rounding value typed xs:double
	 */
	public void testRoundHtE_DOUBLE() throws XPathException {
		/* List of Values to test with Rounding */
		String[] testvalues 	= 
			{ "0.5", "1.5", "2.5", "3.567812E+3", "4.7564E-3", "35612.25" };
		String[] resultvalues	= 
			{ "0.0", "2.0", "2.0", "3567.81",     "0.0",       "35600.0"    };
		int[]	 precision      = 
			{ 0,     0,     0,     2,             2,           -2         };
		
		ResourceSet result 		= null;
		String 		query		= null;
		
		try {
			XPathQueryService service = (XPathQueryService) root.getService( "XQueryService", "1.0" );
			for (int i=0; i<testvalues.length; i++) {
				query = "fn:round-half-to-even( xs:double('" + testvalues[i] + "'), " + precision[i] + " )";
				result = service.query( query );
				String r = (String) result.getResource(0).getContent();
				assertEquals( resultvalues[i], r );
			}
		} catch (XMLDBException e) {
			System.out.println("testRoundHtE_DOUBLE(): "+e);
			fail(e.getMessage());
		}
	}
	
	/** Tests the XQuery-XPath function fn:tokenize() */
	public void testTokenize() throws XPathException {
		ResourceSet result 		= null;
		String		r			= "";
		try {
			result 	= service.query( "count ( tokenize('a/b' , '/') )" );
			r 		= (String) result.getResource(0).getContent();
			assertEquals( "2", r );
			
			result 	= service.query( "count ( tokenize('a/b/' , '/') )" );
			r 		= (String) result.getResource(0).getContent();
			assertEquals( "3", r );
		} catch (XMLDBException e) {
			System.out.println("testTokenize(): " + e);
			fail(e.getMessage());
		}
	}
	
	public void testDistinctValues() throws XPathException {
		ResourceSet result 		= null;
		String		r			= "";
		try {
			result 	= service.query( "declare variable $c { distinct-values(('a', 'a')) }; $c" );
			r 		= (String) result.getResource(0).getContent();
			assertEquals( "a", r );	
			
			result 	= service.query( "declare variable $c { distinct-values((<a>a</a>, <b>a</b>)) }; $c" );
			r 		= (String) result.getResource(0).getContent();
			assertEquals( "a", r );				
		
		} catch (XMLDBException e) {
			System.out.println("testTokenize(): " + e);
			fail(e.getMessage());
		}
	}	
	
	/*
	 * @see TestCase#setUp()
	 */
	protected void setUp() throws Exception {
		// initialize driver
		Class cl = Class.forName("org.exist.xmldb.DatabaseImpl");
		database = (Database) cl.newInstance();
		database.setProperty("create-database", "true");
		DatabaseManager.registerDatabase(database);
		root = DatabaseManager.getCollection("xmldb:exist:///db", "admin", null);
		service = (XPathQueryService) root.getService( "XQueryService", "1.0" );
	}

	/*
	 * @see TestCase#tearDown()
	 */
	protected void tearDown() throws Exception {
		DatabaseManager.deregisterDatabase(database);
		DatabaseInstanceManager dim =
			(DatabaseInstanceManager) root.getService("DatabaseInstanceManager", "1.0");
		dim.shutdown();
		//System.out.println("tearDown PASSED");
	}

}
