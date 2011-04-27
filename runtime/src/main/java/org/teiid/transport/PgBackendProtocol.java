
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
 */package org.teiid.transport;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelDownstreamHandler;
import org.jboss.netty.channel.ChannelEvent;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.teiid.client.util.ResultsFuture;
import org.teiid.core.util.ObjectConverterUtil;
import org.teiid.core.util.ReaderInputStream;
import org.teiid.core.util.ReflectionHelper;
import org.teiid.deployers.PgCatalogMetadataStore;
import org.teiid.jdbc.ResultSetImpl;
import org.teiid.jdbc.TeiidSQLException;
import org.teiid.logging.LogConstants;
import org.teiid.logging.LogManager;
import org.teiid.net.socket.ServiceInvocationStruct;
import org.teiid.odbc.ODBCClientRemote;
import org.teiid.transport.pg.PGbytea;

/**
 * Represents the messages going from Server --> PG ODBC Client  
 * Some parts of this code is taken from H2's implementation of ODBC
 */
@SuppressWarnings("nls")
public class PgBackendProtocol implements ChannelDownstreamHandler, ODBCClientRemote {
	
    private final class ResultsWorkItem implements Runnable {
		private final List<PgColInfo> cols;
		private final String sql;
		private final ResultSetImpl rs;
		private final ResultsFuture<Void> result;

		private ResultsWorkItem(List<PgColInfo> cols, String sql,
				ResultSetImpl rs, ResultsFuture<Void> result) {
			this.cols = cols;
			this.sql = sql;
			this.rs = rs;
			this.result = result;
		}

		@Override
		public void run() {
			while (true) {
				try {
			    	nextFuture = rs.submitNext();
			    	if (!nextFuture.isDone()) {
				    	nextFuture.addCompletionListener(new ResultsFuture.CompletionListener<Boolean>() {
				    		@Override
				    		public void onCompletion(ResultsFuture<Boolean> future) {
				    			if (processRow(future)) {
				    				//this can be recursive, but ideally won't be called many times 
				    				ResultsWorkItem.this.run();
				    			}
				    		}
						});
				    	return;
			    	}
			    	if (!processRow(nextFuture)) {
			    		break;
			    	}
				} catch (Throwable t) {
					result.getResultsReceiver().exceptionOccurred(t);
				}
			}
		}
		
		private boolean processRow(ResultsFuture<Boolean> future) {
			nextFuture = null;
			boolean processNext = true;
			try {
    			if (future.get()) {
    				sendDataRow(rs, cols);
    			} else {
    				sendCommandComplete(sql, 0);
    				result.getResultsReceiver().receiveResults(null);
    				processNext = false;
    			}
			} catch (Throwable t) {
				result.getResultsReceiver().exceptionOccurred(t);
				return false;
			}
			return processNext;
		}
	}

	private static final int PG_TYPE_VARCHAR = 1043;

    private static final int PG_TYPE_BOOL = 16;
    private static final int PG_TYPE_BYTEA = 17;
    private static final int PG_TYPE_BPCHAR = 1042;
    private static final int PG_TYPE_INT8 = 20;
    private static final int PG_TYPE_INT2 = 21;
    private static final int PG_TYPE_INT4 = 23;
    private static final int PG_TYPE_TEXT = 25;
    //private static final int PG_TYPE_OID = 26;
    private static final int PG_TYPE_FLOAT4 = 700;
    private static final int PG_TYPE_FLOAT8 = 701;
    private static final int PG_TYPE_UNKNOWN = 705;
    
    private static final int PG_TYPE_OIDVECTOR = PgCatalogMetadataStore.PG_TYPE_OIDVECTOR;
    private static final int PG_TYPE_OIDARRAY = PgCatalogMetadataStore.PG_TYPE_OIDARRAY;
    private static final int PG_TYPE_CHARARRAY = PgCatalogMetadataStore.PG_TYPE_CHARARRAY;
    private static final int PG_TYPE_TEXTARRAY = PgCatalogMetadataStore.PG_TYPE_TEXTARRAY;
    
    private static final int PG_TYPE_DATE = 1082;
    private static final int PG_TYPE_TIME = 1083;
    private static final int PG_TYPE_TIMESTAMP_NO_TMZONE = 1114;
    private static final int PG_TYPE_NUMERIC = 1700;
    //private static final int PG_TYPE_LO = 14939;
    
    private DataOutputStream dataOut;
    private ByteArrayOutputStream outBuffer;
    private char messageType;
    private Properties props;    
    private Charset encoding = Charset.forName("UTF-8");
    private ReflectionHelper clientProxy = new ReflectionHelper(ODBCClientRemote.class);
    private ChannelHandlerContext ctx;
    private MessageEvent message;
    private int maxLobSize = (2*1024*1024); // 2 MB
    
	private volatile ResultsFuture<Boolean> nextFuture;

    public PgBackendProtocol(int maxLobSize) {
    	this.maxLobSize = maxLobSize;
    }
    
	@Override
	public void handleDownstream(ChannelHandlerContext ctx, ChannelEvent evt) throws Exception {
        if (!(evt instanceof MessageEvent)) {
            ctx.sendDownstream(evt);
            return;
        }

        MessageEvent me = (MessageEvent) evt;
		if (!(me.getMessage() instanceof ServiceInvocationStruct)) {
			ctx.sendDownstream(evt);
            return;
		}
		this.ctx = ctx;
		this.message = me;
		ServiceInvocationStruct serviceStruct = (ServiceInvocationStruct)me.getMessage();

		try {
			Method m = this.clientProxy.findBestMethodOnTarget(serviceStruct.methodName, serviceStruct.args);
			try {
				m.invoke(this, serviceStruct.args);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}		
		} catch (Throwable e) {
			terminate(e);
		}
	}
		
	@Override
	public void initialized(Properties props) {
		this.props = props;
		setEncoding(props.getProperty("client_encoding", "UTF-8"));
	}
	
	@Override
	public void useClearTextAuthentication() {
		try {
			sendAuthenticationCleartextPassword();
		} catch (IOException e) {
			terminate(e);
		}
	}
	
	@Override
	public void authenticationSucess(int processId, int screctKey) {
		try {
			sendAuthenticationOk();
			// server_version, server_encoding, client_encoding, application_name, 
			// is_superuser, session_authorization, DateStyle, IntervalStyle, TimeZone, 
			// integer_datetimes, and standard_conforming_strings. 
			// (server_encoding, TimeZone, and integer_datetimes were not reported 
			// by releases before 8.0; standard_conforming_strings was not reported by 
			// releases before 8.1; IntervalStyle was not reported by releases before 8.4; 
			// application_name was not reported by releases before 9.0.)
			
			sendParameterStatus("client_encoding", PGCharsetConverter.getEncoding(this.encoding));
			sendParameterStatus("DateStyle", this.props.getProperty("DateStyle", "ISO"));
			sendParameterStatus("integer_datetimes", "off");
			sendParameterStatus("is_superuser", "off");
			sendParameterStatus("server_encoding", "SQL_ASCII");
			sendParameterStatus("server_version", "8.1.4");
			sendParameterStatus("session_authorization", this.props.getProperty("user"));
			sendParameterStatus("standard_conforming_strings", "off");
			sendParameterStatus("application_name", this.props.getProperty("application_name", "ODBCClient"));
			
			// TODO PostgreSQL TimeZone
			sendParameterStatus("TimeZone", "CET");
			
			sendBackendKeyData(processId, screctKey);
		} catch (IOException e) {
			terminate(e);
		}
	}

	@Override
	public void prepareCompleted(String preparedName) {
		sendParseComplete();
	}
	
	@Override
	public void bindComplete() {
		sendBindComplete();
	}

	@Override
	public void errorOccurred(String msg) {
		try {
			sendErrorResponse(msg);
		} catch (IOException e) {
			terminate(e);
		}
	}

	@Override
	public void errorOccurred(Throwable t) {
		try {
			sendErrorResponse(t);
		} catch (IOException e) {
			terminate(e);
		}
	}

	@Override
	public void ready(boolean inTransaction, boolean failedTransaction) {
		try {
			sendReadyForQuery(inTransaction, failedTransaction);
		} catch (IOException e) {
			terminate(e);
		}
	}
	
	public void setEncoding(String value) {
		Charset cs = PGCharsetConverter.getCharset(value);
		if (cs != null) {
			this.encoding = cs;
		}
	}

	@Override
	public void sendParameterDescription(ParameterMetaData meta, int[] paramType) {
		try {
			try {
				int count = meta.getParameterCount();
				startMessage('t');
				writeShort(count);
				for (int i = 0; i < count; i++) {
					int type;
					if (paramType != null && paramType[i] != 0) {
						type = paramType[i];
					} else {
						type = convertType(meta.getParameterType(i+1));
					}
					writeInt(type);
				}
				sendMessage();
			} catch (SQLException e) {
				sendErrorResponse(e);
			}			
		} catch (IOException e) {
			terminate(e);
		}
	}

	@Override
	public void sendResultSetDescription(ResultSetMetaData metaData, Statement stmt) {
		try {
			try {
        		List<PgColInfo> cols = getPgColInfo(metaData, stmt);
				sendRowDescription(cols);
			} catch (SQLException e) {
				sendErrorResponse(e);				
			}			
		} catch (IOException e) {
			terminate(e);
		}
	}
	
	@Override
	public void sendResults(final String sql, final ResultSetImpl rs, ResultsFuture<Void> result, boolean describeRows) {
		try {
			if (nextFuture != null) {
				sendErrorResponse(new IllegalStateException("Pending results have not been sent")); //$NON-NLS-1$
			}
        	
			ResultSetMetaData meta = rs.getMetaData();
    		List<PgColInfo> cols = getPgColInfo(meta, rs.getStatement());
        	if (describeRows) {
        		sendRowDescription(cols);
        	}
        	Runnable r = new ResultsWorkItem(cols, sql, rs, result);
        	r.run();    
		} catch (SQLException e) {
			result.getResultsReceiver().exceptionOccurred(e);
		} catch (IOException e) {
			terminate(e);
		}
	}

	@Override
	public void sendUpdateCount(String sql, int updateCount) {
		try {
			sendCommandComplete(sql, updateCount);
		} catch (IOException e) {
			terminate(e);
		}
	}

	@Override
	public void statementClosed() {
		startMessage('3');
		sendMessage();
	}

	@Override
	public void terminated() {
		try {
			trace("channel being terminated");
			this.sendNoticeResponse("Connection closed");
			this.ctx.getChannel().close();
		} catch (IOException e) {
			trace(e.getMessage());
		}
	}
	
	@Override
	public void flush() {
		try {
			this.dataOut.flush();
			this.dataOut = null;
			Channels.write(this.ctx.getChannel(), null);
		} catch (IOException e) {
			terminate(e);
		}		
	}

	@Override
	public void emptyQueryReceived() {
		sendEmptyQueryResponse();
	}
	
	private void terminate(Throwable t) {
		trace("channel being terminated - ", t.getMessage());
		this.ctx.getChannel().close();
	}

	private void sendEmptyQueryResponse() {
		startMessage('I');
		sendMessage();
	}
		
	private void sendCommandComplete(String sql, int updateCount) throws IOException {
		startMessage('C');
		sql = sql.trim().toUpperCase();
		// TODO remove remarks at the beginning
		String tag;
		if (sql.startsWith("INSERT")) {
			tag = "INSERT 0 " + updateCount;
		} else if (sql.startsWith("DELETE")) {
			tag = "DELETE " + updateCount;
		} else if (sql.startsWith("UPDATE")) {
			tag = "UPDATE " + updateCount;
		} else if (sql.startsWith("SELECT") || sql.startsWith("CALL")) {
			tag = "SELECT";
		} else if (sql.startsWith("BEGIN")) {
			tag = "BEGIN";
		} else if (sql.startsWith("COMMIT")) {
			tag = "COMMIT";
		} else if (sql.startsWith("ROLLBACK")) {
			tag = "ROLLBACK";
		} else {
			trace("Check command tag:", sql);
			tag = "UPDATE " + updateCount;
		}
		writeString(tag);
		sendMessage();
	}

	private void sendDataRow(ResultSet rs, List<PgColInfo> cols) throws SQLException, IOException {
		startMessage('D');
		writeShort(cols.size());
		for (int i = 0; i < cols.size(); i++) {
			byte[] bytes = getContent(rs, cols.get(i), i+1);			
			if (bytes == null) {
				writeInt(-1);
			} else {
				writeInt(bytes.length);
				write(bytes);
			}
		}
		sendMessage();
	}

	private byte[] getContent(ResultSet rs, PgColInfo col, int column) throws SQLException, TeiidSQLException, IOException {
		byte[] bytes = null;
		switch (col.type) {
			case PG_TYPE_BOOL:
			case PG_TYPE_BPCHAR:
		    case PG_TYPE_DATE:
		    case PG_TYPE_FLOAT4:
		    case PG_TYPE_FLOAT8:
		    case PG_TYPE_INT2:
		    case PG_TYPE_INT4:
		    case PG_TYPE_INT8:
		    case PG_TYPE_NUMERIC:
		    case PG_TYPE_TIME:
		    case PG_TYPE_TIMESTAMP_NO_TMZONE:
		    case PG_TYPE_VARCHAR:
		    	String value = rs.getString(column);
		    	if (value != null) {
		    		bytes = value.getBytes(this.encoding);
		    	}
		    	break;
		    
		    case PG_TYPE_TEXT:
		    	Clob clob = rs.getClob(column);
		    	if (clob != null) {
		    		bytes = ObjectConverterUtil.convertToByteArray(new ReaderInputStream(clob.getCharacterStream(), this.encoding), this.maxLobSize);
		    	}		        	
		    	break;
		    	
		    case PG_TYPE_BYTEA:
		    	Blob blob = rs.getBlob(column);
		    	if (blob != null) {
		    		try {
			    		bytes = PGbytea.toPGString(ObjectConverterUtil.convertToByteArray(blob.getBinaryStream(), this.maxLobSize)).getBytes(this.encoding);
		    		} catch(OutOfMemoryError e) {
		    			throw new StreamCorruptedException("data too big: " + e.getMessage()); //$NON-NLS-1$ 
		    		}
		    	}
		    	break;
		    	
		    case PG_TYPE_CHARARRAY:
		    case PG_TYPE_TEXTARRAY:
		    case PG_TYPE_OIDARRAY:
		    	{
		    	Object[] obj = (Object[])rs.getObject(column);
		    	if (obj != null) {
		    		StringBuilder sb = new StringBuilder();	
			    	sb.append("{");
			    	boolean first = true;
			    	for (Object o:obj) {
			    		if (!first) {
			    			sb.append(",");
			    		}
			    		else {
			    			first = false;
			    		}
			    		if (col.type == PG_TYPE_TEXTARRAY) {
			    			escapeQuote(sb, o.toString());
			    		}
			    		else {
			    			sb.append(o.toString());
			    		}
			    	}
			    	sb.append("}");
			    	bytes = sb.toString().getBytes(this.encoding);
		    	}
		    	}
		    	break;
		    	
		    case PG_TYPE_OIDVECTOR:
		    	{
		    	Object[] obj = (Object[])rs.getObject(column);
		    	if (obj != null) {
		    		StringBuilder sb = new StringBuilder();	
			    	boolean first = true;
			    	for (Object o:obj) {
			    		if (!first) {
			    			sb.append(" ");
			    		}
			    		else {
			    			first = false;
			    		}
			    		sb.append(o);
			    	}
			    	bytes = sb.toString().getBytes(this.encoding);
		    	}	
		    	}
		    	break;
		    	
		    default:
		    	throw new TeiidSQLException("unknown datatype failed to convert"); 
		}
		return bytes;
	}
	
	public static void escapeQuote(StringBuilder sb, String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '"' || c == '\\') {
				sb.append('\\');
			}

			sb.append(c);
		}
		sb.append('"');
	}	
	
	@Override
	public void sslDenied() {
		ChannelBuffer buffer = ChannelBuffers.directBuffer(1);
		buffer.writeByte('N');
		Channels.write(this.ctx, this.message.getFuture(), buffer, this.message.getRemoteAddress());		
	}
	
	private void sendErrorResponse(Throwable t) throws IOException {
		trace(t.getMessage());
		SQLException e = TeiidSQLException.create(t);
		startMessage('E');
		write('S');
		writeString("ERROR");
		write('C');
		writeString(e.getSQLState());
		write('M');
		writeString(e.getMessage());
		write('D');
		writeString(e.toString());
		write(0);
		sendMessage();
	}

	private void sendNoData() {
		startMessage('n');
		sendMessage();
	}
	
	private static class PgColInfo {
		String name;
		int reloid;
		short attnum;
		int type;
		int precision;
	}

	private void sendRowDescription(List<PgColInfo> cols) throws IOException {
		startMessage('T');
		writeShort(cols.size());
		for (PgColInfo info : cols) {
			writeString(info.name);
			// rel ID
			writeInt(info.reloid);
			// attribute number of the column
			writeShort(info.attnum);
			// data type
			writeInt(info.type);
			// pg_type.typlen
			writeShort(getTypeSize(info.type, info.precision));
			// pg_attribute.atttypmod
			writeInt(-1);
			// text
			writeShort(0);
		}
		sendMessage();
	}

	private List<PgColInfo> getPgColInfo(ResultSetMetaData meta, Statement stmt)
			throws SQLException {
		int columns = meta.getColumnCount();
		ArrayList<PgColInfo> result = new ArrayList<PgColInfo>(columns);
		for (int i = 1; i < columns + 1; i++) {
	 		PgColInfo info = new PgColInfo();
			info.name = meta.getColumnName(i).toLowerCase();
			info.type = meta.getColumnType(i);
			info.type = convertType(info.type);
			info.precision = meta.getColumnDisplaySize(i);
			String name = meta.getColumnName(i);
			String table = meta.getTableName(i);
			String schema = meta.getSchemaName(i);
			if (schema != null) {
				PreparedStatement ps = null;
				try {
					ps = stmt.getConnection().prepareStatement("select attrelid, attnum, typoid from matpg_relatt where attname = ? and relname = ? and nspname = ?");
					ps.setString(1, name);
					ps.setString(2, table);
					ps.setString(3, schema);
					ResultSet rs = ps.executeQuery();
					if (rs.next()) {
						info.reloid = rs.getInt(1);
						info.attnum = rs.getShort(2);
						int specificType = rs.getInt(3);
						if (!rs.wasNull()) {
							info.type = specificType;
						}
					}
				} finally {
					if (ps != null) {
						ps.close();
					}
				}
			}
			result.add(info);
		}
		return result;
	}

	private int getTypeSize(int pgType, int precision) {
		switch (pgType) {
		case PG_TYPE_VARCHAR:
			return Math.max(255, precision + 10);
		default:
			return precision + 4;
		}
	}

	private void sendErrorResponse(String message) throws IOException {
		trace("Exception:", message);
		startMessage('E');
		write('S');
		writeString("ERROR");
		write('C');
		// PROTOCOL VIOLATION
		writeString("08P01");
		write('M');
		writeString(message);
		sendMessage();
	}
	
	private void sendNoticeResponse(String message) throws IOException {
		trace("notice:", message);
		startMessage('N');
		write('S');
		writeString("ERROR");
		write('M');
		writeString(message);
		sendMessage();
	}

	private void sendParseComplete() {
		startMessage('1');
		sendMessage();
	}

	private void sendBindComplete() {
		startMessage('2');
		sendMessage();
	}

	private void sendAuthenticationCleartextPassword() throws IOException {
		startMessage('R');
		writeInt(3);
		sendMessage();
	}

	private void sendAuthenticationOk() throws IOException {
		startMessage('R');
		writeInt(0);
		sendMessage();
	}

	private void sendReadyForQuery(boolean inTransaction, boolean failedTransaction) throws IOException {
		startMessage('Z');
		char c;
		if (failedTransaction) {
			// failed transaction block
			c = 'E';
		}
		else {
			if (inTransaction) {
				// in a transaction block
				c = 'T';				
			} else {
				// idle
				c = 'I';				
			}
		}
		write((byte) c);
		sendMessage();
	}

	private void sendBackendKeyData(int processId, int screctKey) throws IOException {
		startMessage('K');
		writeInt(processId);
		writeInt(screctKey);
		sendMessage();
	}

	private void sendParameterStatus(String param, String value)	throws IOException {
		startMessage('S');
		writeString(param);
		writeString(value);
		sendMessage();
	}
	
	@Override
	public void functionCallResponse(byte[] data) {
		try {
			startMessage('V');
			if (data == null) {
				writeInt(-1);
			}
			else {
				writeInt(data.length);
				write(data);
			}
			sendMessage();
		} catch (IOException e) {
			terminate(e);
		}		
	}
	
	@Override
	public void functionCallResponse(int data) {
		try {
			startMessage('V');
			writeInt(4);
			writeInt(data);
			sendMessage();
		} catch (IOException e) {
			terminate(e);
		}		
	}

	private void writeString(String s) throws IOException {
		write(s.getBytes(this.encoding));
		write(0);
	}

	private void writeInt(int i) throws IOException {
		dataOut.writeInt(i);
	}

	private void writeShort(int i) throws IOException {
		dataOut.writeShort(i);
	}

	private void write(byte[] data) throws IOException {
		dataOut.write(data);
	}

	private void write(int b) throws IOException {
		dataOut.write(b);
	}

	private void startMessage(char newMessageType) {
		this.messageType = newMessageType;
		this.outBuffer = new ByteArrayOutputStream();
		this.dataOut = new DataOutputStream(this.outBuffer);
	}

	private void sendMessage() {
		byte[] buff = outBuffer.toByteArray();
		int len = buff.length;
		this.outBuffer = null;
		this.dataOut = null;
		
		// now build the wire contents.
		ChannelBuffer buffer = ChannelBuffers.directBuffer(len+5);
		buffer.writeByte((byte)this.messageType);
		buffer.writeInt(len+4);
		buffer.writeBytes(buff);
		Channels.write(this.ctx, this.message.getFuture(), buffer, this.message.getRemoteAddress());
	}

	private static void trace(String... msg) {
		LogManager.logTrace(LogConstants.CTX_ODBC, (Object[])msg);
	}
	
	/**
	 * Types.ARRAY is not supported
	 */
    private static int convertType(final int type) {
        switch (type) {
        case Types.BIT:
        case Types.BOOLEAN:
            return PG_TYPE_BOOL;
        case Types.VARCHAR:
            return PG_TYPE_VARCHAR;        
        case Types.CHAR:
            return PG_TYPE_BPCHAR;
        case Types.TINYINT:
        case Types.SMALLINT:
        	return PG_TYPE_INT2;
        case Types.INTEGER:
            return PG_TYPE_INT4;
        case Types.BIGINT:
            return PG_TYPE_INT8;
        case Types.NUMERIC:
        case Types.DECIMAL:
            return PG_TYPE_NUMERIC;
        case Types.FLOAT:
        case Types.REAL:
            return PG_TYPE_FLOAT4;
        case Types.DOUBLE:
            return PG_TYPE_FLOAT8;
        case Types.TIME:
            return PG_TYPE_TIME;
        case Types.DATE:
            return PG_TYPE_DATE;
        case Types.TIMESTAMP:
            return PG_TYPE_TIMESTAMP_NO_TMZONE;
            
        case Types.BLOB:            
        case Types.BINARY:
        case Types.VARBINARY:
        case Types.LONGVARBINARY:
        	return PG_TYPE_BYTEA;
        	
        case Types.LONGVARCHAR:
        case Types.CLOB:            
        	return PG_TYPE_TEXT;
        
        case Types.SQLXML:        	
            return PG_TYPE_TEXT;
            
        default:
            return PG_TYPE_UNKNOWN;
        }
    }
	
}
