package org.exist.storage.statistics;

import org.exist.dom.QName;
import org.exist.dom.SymbolTable;
import org.exist.storage.NodePath;
import org.w3c.dom.Node;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Collects statistics for a single node in the data guide.
 */
class NodeStats {

    private QName qname;
    private int nodeCount = 0;
    private int maxDepth = 0;

    transient private int depth = 0;

    protected NodeStats[] children = null;

    protected NodeStats(QName qname) {
        this.qname = qname;
    }

    public void incDepth() {
        this.depth++;
    }

    public void updateMaxDepth() {
        if (depth > maxDepth)
            maxDepth = depth;
        depth = 0;
    }

    protected void addOccurrence() {
        nodeCount++;
    }

    protected NodeStats addChild(QName qn) {
        if (children != null) {
            for (int i = 0; i < children.length; i++) {
                NodeStats child = children[i];
                if (child.qname.equalsSimple(qn)) {
                    return child;
                }
            }
        }
        if (children == null) {
            children = new NodeStats[1];
        } else {
            NodeStats[] tc = new NodeStats[children.length + 1];
            System.arraycopy(children, 0, tc, 0, children.length);
            children = tc;
        }
        children[children.length - 1] = new NodeStats(qn);
        return children[children.length - 1];
    }

    protected void mergeInto(DataGuide other, NodePath currentPath) {
        NodePath newPath;
        if (qname == null)
            newPath = currentPath;
        else {
            newPath = new NodePath(currentPath);
            newPath.addComponent(qname);
            other.add(newPath, this);
        }

        if (children != null) {
            for (int i = 0; i < children.length; i++) {
                NodeStats child = children[i];
                child.mergeInto(other, newPath);
            }
        }
    }

    protected void mergeStats(NodeStats other) {
        nodeCount += other.nodeCount;
        if (other.maxDepth > maxDepth)
            maxDepth = other.maxDepth;
    }

    protected int getSize() {
        int s = qname == null ? 0 : 1;
        if (children != null) {
            for (int i = 0; i < children.length; i++) {
                s += children[i].getSize();
            }
        }
        return s;
    }

    protected void write(ByteBuffer buffer, SymbolTable symbols) {
        buffer.putShort(symbols.getNSSymbol(qname.getNamespaceURI()));
        buffer.putShort(symbols.getSymbol(qname.getLocalName()));
        buffer.putInt(nodeCount);
        buffer.putInt(maxDepth);

        buffer.putInt(children == null ? 0: children.length);
        if (children != null) {
            for (int i = 0; i < children.length; i++) {
                children[i].write(buffer, symbols);
            }
        }
    }

    protected void read(ByteBuffer buffer, SymbolTable symbols) {
        short nsid = buffer.getShort();
        short localid = buffer.getShort();
        String namespaceURI = symbols.getNamespace(nsid);
        String localName = symbols.getName(localid);
        qname = symbols.getQName(Node.ELEMENT_NODE, namespaceURI,
            localName, "");
        nodeCount = buffer.getInt();
        maxDepth = buffer.getInt();

        int childCount = buffer.getInt();
        if (childCount > 0) {
            children = new NodeStats[childCount];
            for (int i = 0; i < childCount; i++) {
                children[i] = new NodeStats(null);
                children[i].read(buffer, symbols);
            }
        }
    }

    protected void dump(StringBuffer currentPath, List paths) {
        StringBuffer newPath;
        if (qname == null)
            newPath = currentPath;
        else {
            newPath = new StringBuffer(currentPath);
            if (newPath.length() > 0)
                newPath.append(" -> ");
            newPath.append(qname);
            newPath.append('[').append(nodeCount).append(',');
            newPath.append(maxDepth).append(']');
        }
        paths.add(newPath);
        if (children != null) {
            for (int i = 0; i < children.length; i++) {
                NodeStats child = children[i];
                child.dump(newPath, paths);
            }
        }
    }
}
