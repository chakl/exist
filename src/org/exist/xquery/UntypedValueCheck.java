/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2001-03 Wolfgang M. Meier
 *  wolfgang@exist-db.org
 *  http://exist.sourceforge.net
 *  
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *  
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 *  
 *  $Id$
 */
package org.exist.xquery;

import org.exist.dom.DocumentSet;
import org.exist.xquery.parser.XQueryAST;
import org.exist.xquery.util.Error;
import org.exist.xquery.util.ExpressionDumper;
import org.exist.xquery.value.Item;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.ValueSequence;

/**
 * Runtime-value check for untyped atomic values. Converts a value to the
 * required type if possible.
 * 
 * @author wolf
 */
public class UntypedValueCheck extends AbstractExpression {

	private Expression expression;
	private int requiredType;
	private Error error;
    
    public UntypedValueCheck(XQueryContext context, int requiredType, Expression expression) {
        this(context, requiredType, expression, new Error(Error.TYPE_MISMATCH));
    }
    
	public UntypedValueCheck(XQueryContext context, int requiredType, Expression expression, 
            Error error) {
		super(context);
		this.requiredType = requiredType;
		this.expression = expression;
        this.error = error;
	}
	
	/* (non-Javadoc)
     * @see org.exist.xquery.Expression#analyze(org.exist.xquery.Expression)
     */
    public void analyze(Expression parent, int flags) throws XPathException {
        expression.analyze(this, flags);
    }
    
	/* (non-Javadoc)
	 * @see org.exist.xquery.Expression#eval(org.exist.xquery.StaticContext, org.exist.dom.DocumentSet, org.exist.xquery.value.Sequence, org.exist.xquery.value.Item)
	 */
	public Sequence eval(
		Sequence contextSequence,
		Item contextItem)
		throws XPathException {
		Sequence seq = expression.eval(contextSequence, contextItem);
		ValueSequence result = new ValueSequence();
		Item item;
		for(SequenceIterator i = seq.iterate(); i.hasNext(); ) {
			item = i.nextItem();
			//System.out.println(item.getStringValue() + " converting to " + Type.getTypeName(requiredType));
			try {
				result.add(item.convertTo(requiredType));
			} catch (XPathException e) {
                error.addArgs(ExpressionDumper.dump(expression), Type.getTypeName(requiredType),
                        Type.getTypeName(item.getType()));
                throw new XPathException(expression.getASTNode(), error.toString());
			}
		}
		return result;
	}
    
	/* (non-Javadoc)
	 * @see org.exist.xquery.Expression#preselect(org.exist.dom.DocumentSet, org.exist.xquery.StaticContext)
	 */
	public DocumentSet preselect(DocumentSet in_docs)
		throws XPathException {
		return in_docs;
	}

	/* (non-Javadoc)
     * @see org.exist.xquery.Expression#dump(org.exist.xquery.util.ExpressionDumper)
     */
    public void dump(ExpressionDumper dumper) {
        dumper.display("untyped-value-check[");
        dumper.display(Type.getTypeName(requiredType));
        dumper.display(", ");        
        expression.dump(dumper);
        dumper.display("]");
    }
    
    public String toString() {
    	StringBuffer result = new StringBuffer();
    	result.append("untyped-value-check[");
    	result.append(Type.getTypeName(requiredType));
    	result.append(", ");    	
    	result.append(expression.toString());
    	result.append("]");
    	return result.toString();
    }    
    
	/* (non-Javadoc)
	 * @see org.exist.xquery.Expression#returnsType()
	 */
	public int returnsType() {
		return requiredType;
	}

	/* (non-Javadoc)
	 * @see org.exist.xquery.AbstractExpression#getDependencies()
	 */
	public int getDependencies() {
		return expression.getDependencies();
	}

	/* (non-Javadoc)
	 * @see org.exist.xquery.AbstractExpression#resetState()
	 */
	public void resetState() {
		expression.resetState();
	}
	
	/* (non-Javadoc)
	 * @see org.exist.xquery.AbstractExpression#getASTNode()
	 */
	public XQueryAST getASTNode() {
		return expression.getASTNode();
	}
}
