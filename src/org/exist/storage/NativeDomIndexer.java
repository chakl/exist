/*
 * eXist Open Source Native XML Database 
 * 
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Library General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any
 * later version.
 * 
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Library General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Library General Public License
 * along with this program; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 * 
 * $Id$
 */
package org.exist.storage;

import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Iterator;

import org.apache.log4j.Logger;
import org.apache.oro.text.regex.MalformedPatternException;
import org.apache.oro.text.regex.Pattern;
import org.apache.oro.text.regex.PatternCompiler;
import org.apache.oro.text.regex.PatternMatcher;
import org.apache.oro.text.regex.Perl5Compiler;
import org.apache.oro.text.regex.Perl5Matcher;
import org.dbxml.core.DBException;
import org.dbxml.core.data.Value;
import org.dbxml.core.filer.BTreeException;
import org.dbxml.core.indexer.IndexQuery;
import org.exist.dom.ArraySet;
import org.exist.dom.BinaryDocument;
import org.exist.dom.DocumentImpl;
import org.exist.dom.DocumentSet;
import org.exist.dom.NodeImpl;
import org.exist.dom.NodeProxy;
import org.exist.dom.NodeSet;
import org.exist.dom.XMLUtil;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.store.DOMFile;
import org.exist.storage.store.DOMFileIterator;
import org.exist.storage.store.DOMTransaction;
import org.exist.storage.store.NodeIterator;
import org.exist.util.ByteArrayPool;
import org.exist.util.ByteConversion;
import org.exist.util.Collations;
import org.exist.util.Lock;
import org.exist.util.LockException;
import org.exist.util.ReadOnlyException;
import org.exist.xquery.Constants;
import org.exist.xquery.TerminatedException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Class which encapsulates the DOMFile implementation.
 */
final class NativeDomIndexer {
    
    /** Regular expression compiler */
    private PatternCompiler compiler = new Perl5Compiler();
    
    /** Compiled regular expression matcher */
    private PatternMatcher matcher = new Perl5Matcher();
    
    /** Temporary DBBroker instance */
    private DBBroker broker = null;
    
    /** Is any of the databases read-only? */
    private boolean readOnly = false;
    
    /** The underlying native db */
    private DOMFile domDb = null;
    
    /** The Log4J logger */
    private static final Logger LOG = Logger.getLogger(NativeDomIndexer.class);

    /**
     * Create a new NativeDomIndexer. The DOMFile
     * must be initialized when calling this constructor.
     * 
     * Refactor note: currently, DBBroker is used here, but
     * this should <b>not</b> be the case in future.
     *  
     * @param broker the broker to use
     * @param domDb initialized domDb
     */
    public NativeDomIndexer(DBBroker broker, DOMFile domDb) {
        this.broker = broker;
        this.domDb = domDb;
    }
    
    /**
     * Set read-only if any of the backend datastores
     * are set read-only.
     * 
     * @param readOnly true, if one backend db is read-only
     */
    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }
    
    public Iterator getDOMIterator(NodeProxy proxy) {
        try {
            return new DOMFileIterator(this, domDb, proxy);
        } catch (BTreeException e) {
            LOG.debug("failed to create DOM iterator", e);
        } catch (IOException e) {
            LOG.debug("failed to create DOM iterator", e);
        }
        return null;
    }
    
    public Iterator getNodeIterator(NodeProxy proxy) {
//      domDb.setOwnerObject(this);
        try {
            return new NodeIterator(this, domDb, proxy, false);
        } catch (BTreeException e) {
            LOG.debug("failed to create node iterator", e);
        } catch (IOException e) {
            LOG.debug("failed to create node iterator", e);
        }
        return null;
    }
    
    public String getNodeValue(final NodeProxy proxy) {
        return (String) new DOMTransaction(this, domDb, Lock.READ_LOCK) {
            public Object start() {
                return domDb.getNodeValue(proxy);
            }
        }
        .run();
    }
    
    /**
     * Physically insert a node into the DOM storage.
     */
    public void insertAfter(final NodeImpl previous, final NodeImpl node) {
        final byte data[] = node.serialize();
        final DocumentImpl doc = (DocumentImpl) previous.getOwnerDocument();
        new DOMTransaction(this, domDb, Lock.WRITE_LOCK, doc) {
            public Object start() {
                long address = previous.getInternalAddress();
                if (address > -1) {
                    address = domDb.insertAfter(doc, address, data);
                } else {
                    NodeRef ref = new NodeRef(doc.getDocId(), previous.getGID());
                    address = domDb.insertAfter(doc, ref, data);
                }
                node.setInternalAddress(address);
                return null;
            }
        }
        .run();
    }
    
    public void update(final NodeImpl node) {
        try {
            final DocumentImpl doc = (DocumentImpl) node.getOwnerDocument();
            final long internalAddress = node.getInternalAddress();
            final byte[] data = node.serialize();
            new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
                public Object start() throws ReadOnlyException {
                    if (-1 < internalAddress)
                        domDb.update(internalAddress, data);
                    else {
                        domDb.update(new NodeRef(doc.getDocId(), node.getGID()), data);
                    }
                    return null;
                }
            }
            .run();
            ByteArrayPool.releaseByteArray(data);
        } catch (Exception e) {
            Value oldVal = domDb.get(node.getInternalAddress());
            NodeImpl old = 
                NodeImpl.deserialize(oldVal.data(), oldVal.start(), oldVal.getLength(), 
                        (DocumentImpl)node.getOwnerDocument(), false);
            LOG.debug(
                "Exception while storing "
                    + node.getNodeName()
                    + "; gid = "
                    + node.getGID()
                    + "; old = " + old.getNodeName(),
                e);
        }
    }
    
    public void closeDocument() {
        new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
            public Object start() {
                domDb.closeDocument();
                return null;
            }
        }
        .run();
    }
    
    public void readDocumentMetadata(final DocumentImpl doc) {
        new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
            public Object start() throws ReadOnlyException {
                final Value val = domDb.get(doc.getAddress());
                doc.deserialize(val.getData());
                return null;
            }
        }
        .run();

    }
    
    public void storeDocument(final DocumentImpl doc) {
        final byte data[] = doc.serialize();
        new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
            public Object start() throws ReadOnlyException {
                if (doc.getAddress() > -1) {
                    domDb.remove(doc.getAddress());
                }
                doc.setAddress(domDb.add(data));
//              LOG.debug("Document metadata stored to " + StorageAddress.toString(doc.getAddress()));
                return null;
            }
        }
        .run();
    }
    
    public void checkTree(final DocumentImpl doc) {
        LOG.debug("Checking DOM tree for document " + doc.getFileName());
        if(broker.consistencyChecksEnabled()) {
            new DOMTransaction(this, domDb, Lock.READ_LOCK) {
                public Object start() throws ReadOnlyException {
                    LOG.debug("Pages used: " + domDb.debugPages(doc));
                    return null;
                }
            }.run();
            
            NodeList nodes = doc.getChildNodes();
            NodeImpl n;
            for (int i = 0; i < nodes.getLength(); i++) {
                n = (NodeImpl) nodes.item(i);
                Iterator iterator =
                    getNodeIterator(
                            new NodeProxy(doc, n.getGID(), n.getInternalAddress()));
                iterator.next();
                checkTree(iterator, n);
            }
            NodeRef ref = new NodeRef(doc.getDocId());
            final IndexQuery idx = new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);
            new DOMTransaction(this, domDb) {
                public Object start() {
                    try {
                        domDb.findKeys(idx);
                    } catch (BTreeException e) {
                        LOG.warn("start() - " + "error while removing doc", e);
                    } catch (IOException e) {
                        LOG.warn("start() - " + "error while removing doc", e);
                    }
                    return null;
                }
            }
            .run();
        }
    }
    
    private void checkTree(Iterator iterator, NodeImpl node) {
        if (node.hasChildNodes()) {
            final long firstChildId = XMLUtil.getFirstChildId((DocumentImpl)node.getOwnerDocument(), 
                    node.getGID());
            if (firstChildId < 0) {
                LOG.fatal(
                    "no child found: expected = "
                        + node.getChildCount()
                        + "; node = "
                        + node.getNodeName()
                        + "; gid = "
                        + node.getGID());
                throw new IllegalStateException("wrong node id");
            }
            final long lastChildId = firstChildId + node.getChildCount();
            NodeImpl child;
            for (long gid = firstChildId; gid < lastChildId; gid++) {
                child = (NodeImpl) iterator.next();
                if(child == null)
                    LOG.debug("child " + gid + " not found for node: " + node.getNodeName() +
                            "; last = " + lastChildId + "; children = " + node.getChildCount());
                child.setGID(gid);
                checkTree(iterator, child);
            }
        }
    }
    
    public void removeBinaryResource(final BinaryDocument blob)
    throws PermissionDeniedException {
    if (readOnly)
        throw new PermissionDeniedException(NewNativeBroker.DATABASE_IS_READ_ONLY);
    LOG.info("removing binary resource " + blob.getDocId() + "...");
    new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
        public Object start() throws ReadOnlyException {
            domDb.remove(blob.getAddress());
            domDb.removeOverflowValue(blob.getPage());
            return null;
        }
    }
    .run();
    }
    
    public byte[] getBinaryResourceData(final BinaryDocument blob) {
        byte[] data = (byte[]) new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
            public Object start() throws ReadOnlyException {
                return domDb.getBinary(blob.getPage());
            }
        }
        .run();
        return data;
    }
    
    public void storeBinaryResource(final BinaryDocument blob, final byte[] data) {
        new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
            public Object start() throws ReadOnlyException {
//              if (blob.getPage() > -1) {
//                  domDb.remove(blob.getPage());
//              }
                LOG.debug("Storing binary resource " + blob.getFileName());
                blob.setPage(domDb.addBinary(data));
                return null;
            }
        }
        .run();
    }
    
    public Node objectWith(final Document doc, final long gid) {
        return (Node) new DOMTransaction(this, domDb) {
            public Object start() {
                Value val = domDb.get(new NodeProxy((DocumentImpl) doc, gid));
                if (val == null) {
//                  if(LOG.isDebugEnabled()) {
//                      LOG.debug("node " + gid + " not found in document " + ((DocumentImpl)doc).getDocId());
//                      Thread.dumpStack();
//                  }
                    return null;
                }
                NodeImpl node =
                    NodeImpl.deserialize(
                        val.getData(),
                        0,
                        val.getLength(),
                        (DocumentImpl) doc);
                node.setGID(gid);
                node.setOwnerDocument(doc);
                node.setInternalAddress(val.getAddress());
                return node;
            }
        }
        .run();
    }
    
    public Node objectWith(final NodeProxy p) {
        if (p.getInternalAddress() < 0)
            return objectWith(p.getDocument(), p.gid);
        return (Node) new DOMTransaction(this, domDb) {
            public Object start() {
                Value val = domDb.get(p.getInternalAddress());
                if (val == null) {
                    LOG.debug("Node " + p.gid + " not found in document " + p.getDocument().getName() +
                            "; docId = " + p.getDocument().getDocId());
//                  LOG.debug(domDb.debugPages(p.doc));
                    Thread.dumpStack();
//                  return null;
                    return objectWith(p.getDocument(), p.gid); // retry?
                }
                NodeImpl node =
                    NodeImpl.deserialize(
                        val.getData(),
                        0,
                        val.getLength(),
                        (DocumentImpl) p.getDocument());
                node.setGID(p.gid);
                node.setOwnerDocument(p.getDocument());
                node.setInternalAddress(p.getInternalAddress());
                return node;
            }
        }
        .run();
    }
    
    /**
     *  Do a sequential search through the DOM-file.
     *
     *@param  context     Description of the Parameter
     *@param  doc         Description of the Parameter
     *@param  relation    Description of the Parameter
     *@param  truncation  Description of the Parameter
     *@param  expr        Description of the Parameter
     *@return             Description of the Return Value
     */
    public NodeSet scanSequential(
        NodeSet context,
        DocumentSet doc,
        int relation,
        int truncation,
        String expr,
        Collator collator) {
        ArraySet resultNodeSet = new ArraySet(context.getLength());
        NodeProxy p;
        String content;
        String cmp;
        Pattern regexp = null;
        if (relation == Constants.REGEXP)
            try {
                regexp =
                    compiler.compile(
                        expr.toLowerCase(),
                        Perl5Compiler.CASE_INSENSITIVE_MASK);
                truncation = Constants.REGEXP;
            } catch (MalformedPatternException e) {
                LOG.debug(e);
            }
        for (Iterator i = context.iterator(); i.hasNext();) {
            p = (NodeProxy) i.next();
            try {
                domDb.getLock().acquire(Lock.READ_LOCK);
                domDb.setOwnerObject(this);
                content = domDb.getNodeValue(p);
            } catch (LockException e) {
                LOG.warn("failed to acquire read lock on dom.dbx");
                continue;
            } finally {
                domDb.getLock().release();
            }
            if (broker.isCaseSensitive())
                cmp = content;
            else {
                cmp = content.toLowerCase();
            }
            //System.out.println("context = " + p.gid + "; context-length = " + 
            //  (p.getContext() == null ? -1 : p.getContext().getSize()));
            switch (truncation) {
                case Constants.TRUNC_LEFT :
                    if (Collations.endsWith(collator, cmp, expr))
                        resultNodeSet.add(p);
                    break;
                case Constants.TRUNC_RIGHT :
                    if (Collations.startsWith(collator, cmp, expr))
                        resultNodeSet.add(p);
                    break;
                case Constants.TRUNC_BOTH :
                    if (-1 < Collations.indexOf(collator, cmp, expr))
                        resultNodeSet.add(p);
                    break;
                case Constants.TRUNC_NONE :
                    if (compare(collator, cmp, expr, relation))
                        resultNodeSet.add(p);
                    break;
                case Constants.REGEXP :
                    if (regexp != null && matcher.contains(cmp, regexp)) {
                        resultNodeSet.add(p);
                    }
                    break;
            }
        }
        return resultNodeSet;
    }
    
    public final static class NodeRef extends Value {
        /**
         * Log4J Logger for this class
         */
        private static final Logger LOG = Logger.getLogger(NodeRef.class);

        public NodeRef() {
            data = new byte[12];
        }

        public NodeRef(int docId, long gid) {
            data = new byte[12];
            ByteConversion.intToByte(docId, data, 0);
            ByteConversion.longToByte(gid, data, 4);
            len = 12;
            pos = 0;
        }

        public NodeRef(int docId) {
            data = new byte[4];
            ByteConversion.intToByte(docId, data, 0);
            len = 4;
            pos = 0;
        }

        int getDocId() {
            return ByteConversion.byteToInt(data, 0);
        }

        long getGid() {
            return ByteConversion.byteToLong(data, 4);
        }

        void set(int docId, long gid) {
            ByteConversion.intToByte(docId, data, 0);
            ByteConversion.longToByte(gid, data, 4);
            len = 12;
            pos = 0;
        }
    }
    
    private final boolean compare(Collator collator, String o1, String o2, int relation) {
        int cmp = Collations.compare(collator, o1, o2);
        switch (relation) {
            case Constants.LT :
                return (cmp < 0);
            case Constants.LTEQ :
                return (cmp <= 0);
            case Constants.GT :
                return (cmp > 0);
            case Constants.GTEQ :
                return (cmp >= 0);
            case Constants.EQ :
                return (cmp == 0);
            case Constants.NEQ :
                return (cmp != 0);
        }
        return false;
        // never reached
    }

    /////// DB FUNCS
    public void sync(int syncEvent) {
        new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
            public Object start() {
                try {
                    domDb.flush();
                } catch (DBException e) {
                    LOG.warn("error while flushing dom.dbx", e);
                }
                return null;
            }
        }
        .run();
    }
    
    public void printStatistics() {
        domDb.printStatistics();
    }
    
    public boolean close() throws DBException {
        return domDb.close();
    }
    /////// DB FUNCS
    
    ////// new funcs
    public void n1(final DocumentImpl doc, final NodeImpl node, final long gid) {
        new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
            public Object start() throws ReadOnlyException {
                try {
                    domDb.addValue(
                        new NodeRef(doc.getDocId(), gid),
                        node.getInternalAddress());
                } catch (BTreeException e) {
                    LOG.warn(NewNativeBroker.EXCEPTION_DURING_REINDEX, e);
                } catch (IOException e) {
                    LOG.warn(NewNativeBroker.EXCEPTION_DURING_REINDEX, e);
                }
                return null;
            }
        }
        .run();
    }
    
    public void n2(final DocumentImpl doc, final DocumentImpl oldDoc,
            final NodeImpl node) {
        new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
            public Object start() throws ReadOnlyException {
                try {
                    Value ref = new NodeRef(doc.getDocId());
                    IndexQuery query = new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);
                    final ArrayList nodes = domDb.findKeys(query);
                    long gid;
                    for (Iterator i = nodes.iterator(); i.hasNext();) {
                        ref = (Value) i.next();
                        gid = ByteConversion.byteToLong(ref.data(), ref.start() + 4);
                        if (oldDoc.getTreeLevel(gid) >= doc.reindexRequired()) {
                            if (node != null) {
                                if (XMLUtil.isDescendant(oldDoc, node.getGID(), gid)) {
                                    domDb.removeValue(ref);
                                }
                            } else
                                domDb.removeValue(ref);
                        }
                    }
                } catch (BTreeException e) {
                    LOG.debug("Exception while reindexing document: " + e.getMessage(), e);
                } catch (IOException e) {
                    LOG.debug("Exception while reindexing document: " + e.getMessage(), e);
                }
                return null;
            }
        }.run();
    }
    
    public void n3(final DocumentImpl doc, final NodeImpl node) {
        new DOMTransaction(this, domDb, Lock.WRITE_LOCK, doc) {
            public Object start() {
                final long address = node.getInternalAddress();
                if (address > -1)
                    domDb.remove(new NodeRef(doc.getDocId(), node.getGID()), address);
                else
                    domDb.remove(new NodeRef(doc.getDocId(), node.getGID()));
                return null;
            }
        }
        .run();
    }
    
    public void n4(final DocumentImpl doc, final NodeImpl node,
            final long gid) {
        new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
            public Object start() throws ReadOnlyException {
                try {
                    domDb.addValue(
                        new NodeRef(doc.getDocId(), gid),
                        node.getInternalAddress());
                } catch (BTreeException e) {
                    LOG.warn(NewNativeBroker.EXCEPTION_DURING_REINDEX, e);
                } catch (IOException e) {
                    LOG.warn(NewNativeBroker.EXCEPTION_DURING_REINDEX, e);
                }
                return null;
            }
        }
        .run();
    }
    
    public void removeDocument(final DocumentImpl doc) {
        NodeRef ref = new NodeRef(doc.getDocId());
        final IndexQuery idx = new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);
        new DOMTransaction(this, domDb) {
            public Object start() {
                try {
                    domDb.remove(idx, null);
                    domDb.flush();
                } catch (BTreeException e) {
                    LOG.warn("start() - " + "error while removing doc", e);
                } catch (IOException e) {
                    LOG.warn("start() - " + "error while removing doc", e);
                } catch (TerminatedException e) {
                    LOG.warn("method terminated", e);
                } catch (DBException e) {
                    LOG.warn("start() - " + "error while removing doc", e);
                }
                return null;
            }
        }
        .run();
    }
    
    // See n7 first part!
    public void n6(final long firstChild) {
        new DOMTransaction(this, domDb) {
            public Object start() {
                domDb.removeAll(firstChild);
                try {
                    domDb.flush();
                } catch (DBException e) {
                    LOG.warn("start() - " + "error while removing doc", e);
                }
                return null;
            }
        }
        .run();
    }
    
    public void n7(final DocumentImpl document) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("removeDocument() - removing dom");
        }
        
        new DOMTransaction(this, domDb) {
            public Object start() {
                NodeImpl node = (NodeImpl)document.getFirstChild();
                domDb.removeAll(node.getInternalAddress());
                return null;
            }
        }
        .run();
        
        removeDocument(document);
        /* replaced by removeDocument(document);
        NodeRef ref = new NodeRef(document.getDocId());
        final IndexQuery idx = new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);
        new DOMTransaction(this, domDb) {
            public Object start() {
                try {
                    domDb.remove(idx, null);
                    domDb.flush();
                } catch (BTreeException e) {
                    LOG.warn("start() - " + "error while removing doc", e);
                } catch (DBException e) {
                    LOG.warn("start() - " + "error while removing doc", e);
                } catch (IOException e) {
                    LOG.warn("start() - " + "error while removing doc", e);
                } catch (TerminatedException e) {
                    LOG.warn("method terminated", e);
                }
                return null;
            }
        }
        .run();
        */
    }
    
    public void n8(final DocumentImpl doc, final NodeImpl node, 
            final short nodeType, final long gid, final int depth) {
        
        
        new DOMTransaction(this, domDb, Lock.WRITE_LOCK, doc) {
            public Object start() throws ReadOnlyException {
                long address = -1;
                final byte data[] = node.serialize();
                if (nodeType == Node.TEXT_NODE
                    || nodeType == Node.ATTRIBUTE_NODE
                    || doc.getTreeLevel(gid) > depth)
                    address = domDb.add(data);
                else {
                    address = domDb.put(new NodeRef(doc.getDocId(), gid), data);
                }
                if (address < 0)
                    LOG.warn("address is missing");
                node.setInternalAddress(address);
                ByteArrayPool.releaseByteArray(data);
                return null;
            }
        }
        .run();
    }
    
    // TODO check if this is ok with external "lock" of collection cache
    // first part can be replaced by removeBinaryResource and by n7
    public void n9(final DocumentImpl doc) {
        LOG.debug("removing document " + doc.getFileName());
        new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
            public Object start() {
                if(doc.getResourceType() == DocumentImpl.BINARY_FILE) {
                    domDb.remove(doc.getAddress());
                    domDb.removeOverflowValue(((BinaryDocument)doc).getPage());
                } else {
                    NodeImpl node = (NodeImpl)doc.getFirstChild();
                    domDb.removeAll(node.getInternalAddress());
                }
                return null;
            }
        }
        .run();
        
        dropIndex(doc);
        /* replaced by dropIndex
        new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
            public Object start() {
                try {
                    Value ref = new NodeRef(doc.getDocId());
                    IndexQuery query =
                        new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);
                    domDb.remove(query, null);
                    domDb.flush();
                } catch (BTreeException e) {
                    LOG.warn("btree error while removing document", e);
                } catch (DBException e) {
                    LOG.warn("db error while removing document", e);
                } catch (IOException e) {
                    LOG.warn("io error while removing document", e);
                } catch (TerminatedException e) {
                    LOG.warn("method terminated", e);
                }
                return null;
            }
        }
        .run();
        */
    }
    
    public void dropIndex(final DocumentImpl doc) {
        LOG.debug("Dropping index for document " + doc.getFileName());
        
        new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
            public Object start() {
                try {
                    Value ref = new NodeRef(doc.getDocId());
                    IndexQuery query =
                        new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);
                    domDb.remove(query, null);
                    domDb.flush();
                } catch (BTreeException e) {
                    LOG.warn("btree error while removing document", e);
                } catch (DBException e) {
                    LOG.warn("db error while removing document", e);
                } catch (IOException e) {
                    LOG.warn("io error while removing document", e);
                } catch (TerminatedException e) {
                    LOG.warn("method terminated", e);
                }
                return null;
            }
        }
        .run();
    }
}
