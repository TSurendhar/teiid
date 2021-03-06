/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */
package org.teiid.translator.hive;

import static org.teiid.language.SQLConstants.Reserved.*;

import java.util.List;

import org.teiid.core.util.StringUtil;
import org.teiid.language.*;
import org.teiid.language.Join.JoinType;
import org.teiid.language.SQLConstants.Tokens;
import org.teiid.translator.TypeFacility;
import org.teiid.translator.jdbc.SQLConversionVisitor;

public class HiveSQLConversionVisitor extends SQLConversionVisitor {

	BaseHiveExecutionFactory baseHiveExecutionFactory;
	
	public HiveSQLConversionVisitor(BaseHiveExecutionFactory hef) {
		super(hef);
		this.baseHiveExecutionFactory = hef;
	}
	
    @Override
	public void visit(Join obj) {
        TableReference leftItem = obj.getLeftItem();
        TableReference rightItem = obj.getRightItem();
        JoinType joinType = obj.getJoinType();
        
        //impala only supports a left linear join
        if (baseHiveExecutionFactory.requiresLeftLinearJoin() && rightItem instanceof Join) {
        	if (leftItem instanceof Join) {
        		//TODO: this may need to be handled in the engine to inhibit pushdown
        		throw new AssertionError("A left linear join structure is required: " + obj); //$NON-NLS-1$
        	}
        	
        	//swap
        	TableReference tr = leftItem;
        	leftItem = rightItem;
        	rightItem = tr;
        	
        	if (joinType == JoinType.RIGHT_OUTER_JOIN) {
        		joinType = JoinType.LEFT_OUTER_JOIN;
        	} else if (joinType == JoinType.LEFT_OUTER_JOIN) {
        		joinType = JoinType.RIGHT_OUTER_JOIN;
        	}
        }
        
        if(useParensForJoins() && leftItem instanceof Join) {
            buffer.append(Tokens.LPAREN);
            append(leftItem);
            buffer.append(Tokens.RPAREN);
        } else {
            append(leftItem);
        }
        buffer.append(Tokens.SPACE);
        
        switch(joinType) {
            case CROSS_JOIN:
            	// Hive just works with "JOIN" keyword no inner or cross
            	// fixed in - https://issues.apache.org/jira/browse/HIVE-2549
                buffer.append(CROSS); 
                break;
            case FULL_OUTER_JOIN:
                buffer.append(FULL)
                      .append(Tokens.SPACE)
                      .append(OUTER);
                break;
            case INNER_JOIN:
            	// Hive just works with "JOIN" keyword no inner or cross
                //buffer.append(INNER);
                break;
            case LEFT_OUTER_JOIN:
                buffer.append(LEFT)
                      .append(Tokens.SPACE)
                      .append(OUTER);
                break;
            case RIGHT_OUTER_JOIN:
                buffer.append(RIGHT)
                      .append(Tokens.SPACE)
                      .append(OUTER);
                break;
            default: buffer.append(UNDEFINED);
        }
        buffer.append(Tokens.SPACE)
              .append(JOIN)
              .append(Tokens.SPACE);
        
        if(rightItem instanceof Join && (useParensForJoins() || obj.getJoinType() == Join.JoinType.CROSS_JOIN)) {
            buffer.append(Tokens.LPAREN);
            append(rightItem);
            buffer.append(Tokens.RPAREN);
        } else {
            append(rightItem);
        }
        
        final Condition condition = obj.getCondition();
        if (condition != null) {
            buffer.append(Tokens.SPACE)
                  .append(ON)
                  .append(Tokens.SPACE);
            append(condition);                    
        }        
    }	
    
    public void addColumns(List<DerivedColumn> items) {
        if (items != null && items.size() != 0) {
        	addColumn(items.get(0));
            for (int i = 1; i < items.size(); i++) {
                buffer.append(Tokens.COMMA)
                      .append(Tokens.SPACE);
                addColumn(items.get(i));
            }
        }    	
    }

	private void addColumn(DerivedColumn dc) {
		if (dc.getAlias() != null) {
		    buffer.append(dc.getAlias());
		}
		else {
			Expression expr = dc.getExpression();
			if (expr instanceof ColumnReference) {
				buffer.append(((ColumnReference)expr).getName());
			}
			else {
				append(expr);
			}
		}
	}
    
    @Override
    public void visit(SetQuery obj) {
    	if (obj.getWith() != null) {
    		append(obj.getWith());
    	}
    	
    	Select select =  (Select)obj.getLeftQuery();
    	buffer.append(SELECT).append(Tokens.SPACE);
    	if(!obj.isAll()) {
    		buffer.append(DISTINCT).append(Tokens.SPACE);
    	}
    	addColumns(select.getDerivedColumns());
    	buffer.append(Tokens.SPACE);
    	buffer.append(FROM).append(Tokens.SPACE);
    	buffer.append(Tokens.LPAREN);
    	 
        appendSetQuery(obj, obj.getLeftQuery(), false);
        
        buffer.append(Tokens.SPACE);
        
        appendSetOperation(obj.getOperation());

        // UNION "ALL" always
        buffer.append(Tokens.SPACE);
        buffer.append(ALL);                
        buffer.append(Tokens.SPACE);

        appendSetQuery(obj, obj.getRightQuery(), true);
        
        OrderBy orderBy = obj.getOrderBy();
        if(orderBy != null) {
            buffer.append(Tokens.SPACE);
            append(orderBy);
        }

        Limit limit = obj.getLimit();
        if(limit != null) {
            buffer.append(Tokens.SPACE);
            append(limit);
        }
        buffer.append(Tokens.RPAREN);
        buffer.append(Tokens.SPACE);
        buffer.append("X__"); //$NON-NLS-1$
    }
    
    @Override
    public void visit(Select obj) {
    	if (obj.getGroupBy() != null && obj.getOrderBy() != null) {
    		//hive does not like order by with a group by using the full column references
    		//this should be fine even with joins as the engine should alias the select columns 
			for (SortSpecification spec : obj.getOrderBy().getSortSpecifications()) {
				if (spec.getExpression() instanceof ColumnReference) {
					ColumnReference cr = (ColumnReference)spec.getExpression();
					cr.setTable(null);
				}
			}
    	}
    	super.visit(obj);
    }
    
    @Override
    protected void translateSQLType(Class<?> type, Object obj,
    		StringBuilder valuesbuffer) {
    	if (obj != null && type == TypeFacility.RUNTIME_TYPES.STRING) {
    		String val = obj.toString();
    		valuesbuffer.append(Tokens.QUOTE)
	          .append(StringUtil.replaceAll(StringUtil.replaceAll(val, "\\", "\\\\"), "'", "\\'")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	          .append(Tokens.QUOTE);
    	} else {
    		super.translateSQLType(type, obj, valuesbuffer);
    	}
    }
}
