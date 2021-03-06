/*
 * This file is part of AceQL HTTP.
 * AceQL HTTP: SQL Over HTTP                                     
 * Copyright (C) 2018, KawanSoft SAS
 * (http://www.kawansoft.com). All rights reserved.                                
 *                                                                               
 * AceQL HTTP is free software; you can redistribute it and/or                 
 * modify it under the terms of the GNU Lesser General Public                    
 * License as published by the Free Software Foundation; either                  
 * version 2.1 of the License, or (at your option) any later version.            
 *                                                                               
 * AceQL HTTP is distributed in the hope that it will be useful,               
 * but WITHOUT ANY WARRANTY; without even the implied warranty of                
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU             
 * Lesser General Public License for more details.                               
 *                                                                               
 * You should have received a copy of the GNU Lesser General Public              
 * License along with this library; if not, write to the Free Software           
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  
 * 02110-1301  USA
 * 
 * Any modifications to this file must keep this entire header
 * intact.
 */
package org.kawanfw.sql.servlet;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Date;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.tomcat.util.http.fileupload.FileUploadException;
import org.apache.tomcat.util.http.fileupload.servlet.ServletFileUpload;
import org.kawanfw.sql.api.server.DatabaseConfigurator;
import org.kawanfw.sql.api.server.blob.BlobDownloadConfigurator;
import org.kawanfw.sql.api.server.blob.BlobUploadConfigurator;
import org.kawanfw.sql.servlet.connection.ConnectionStore;
import org.kawanfw.sql.servlet.connection.ConnectionStoreCleaner;
import org.kawanfw.sql.servlet.connection.SavepointUtil;
import org.kawanfw.sql.servlet.connection.TransactionUtil;
import org.kawanfw.sql.servlet.sql.LoggerUtil;
import org.kawanfw.sql.servlet.sql.ServerStatement;
import org.kawanfw.sql.servlet.sql.callable.ServerCallableStatement;
import org.kawanfw.sql.servlet.sql.json_return.JsonErrorReturn;
import org.kawanfw.sql.servlet.sql.json_return.JsonOkReturn;
import org.kawanfw.sql.servlet.util.BlobUtil;
import org.kawanfw.sql.util.FrameworkDebug;

/**
 * @author Nicolas de Pomereu
 * 
 *         The method executeRequest() is to to be called from the SqlHttpServer
 *         Servlet and Class. <br>
 *         It will execute a client side request with a RemoteConnection
 *         connection.
 * 
 */
public class ServerSqlDispatch {

	private static boolean DEBUG = FrameworkDebug.isSet(ServerSqlDispatch.class);

	/**
	 * Constructor
	 */
	public ServerSqlDispatch() {
		// Does nothing
	}

	/**
	 * Execute the client sent sql request that is already wrapped in the calling
	 * try/catch that handles Throwable
	 * 
	 * @param request
	 *            the http request
	 * @param response
	 *            the http response
	 * @param out
	 *            the output stream to write result to client
	 * @throws IOException
	 *             if any IOException occurs
	 * @throws SQLException
	 * @throws FileUploadException
	 */
	public void executeRequestInTryCatch(HttpServletRequest request, HttpServletResponse response, OutputStream out)
			throws IOException, SQLException, FileUploadException {

		// Immediate catch if we are asking a file upload, because
		// parameters are in unknown sequence.
		// We know it's a upload action if it's mime Multipart
		if (ServletFileUpload.isMultipartContent(request)) {
			blobUpload(request, response);
			return;
		}

		debug("executeRequest Start");

		// Prepare the response
		response.setContentType("text/html; charset=UTF-8");

		// Get the send string
		debug("ACTION retrieval");

		String action = request.getParameter(HttpParameter.ACTION);
		String username = request.getParameter(HttpParameter.USERNAME);
		String sessionId = request.getParameter(HttpParameter.SESSION_ID);
		String connectionId = request.getParameter(HttpParameter.CONNECTION_ID);
		String database = request.getParameter(HttpParameter.DATABASE);

		if (action == null || action.isEmpty()) {

			out = response.getOutputStream();
			JsonErrorReturn errorReturn = new JsonErrorReturn(response, HttpServletResponse.SC_BAD_REQUEST,
					JsonErrorReturn.ERROR_ACEQL_ERROR, JsonErrorReturn.NO_ACTION_FOUND_IN_REQUEST);
			ServerSqlManager.writeLine(out, errorReturn.build());

			return;
		}

		debug("ACTION: " + action);

		debug("test action.equals(HttpParameter.LOGIN)");

		if (action.equals(HttpParameter.LOGIN) || action.equals(HttpParameter.CONNECT)) {
			ServerLoginActionSql serverLoginActionSql = new ServerLoginActionSql();
			serverLoginActionSql.executeAction(request, response, action);
			return;
		}

		debug("ACTION : " + action);

		DatabaseConfigurator databaseConfigurator = ServerSqlManager.getDatabaseConfigurator(database);

		if (databaseConfigurator == null) {
			out = response.getOutputStream();
			JsonErrorReturn errorReturn = new JsonErrorReturn(response, HttpServletResponse.SC_BAD_REQUEST,
					JsonErrorReturn.ERROR_ACEQL_ERROR, JsonErrorReturn.DATABASE_DOES_NOT_EXIST + database);
			ServerSqlManager.writeLine(out, errorReturn.build());
			return;
		}

		//
		if (action.equals(HttpParameter.GET_CONNECTION)) {
			out = response.getOutputStream();
			connectionId = ServerLoginActionSql.getConnectionId(sessionId, request, username, database,
					databaseConfigurator);
			ServerSqlManager.writeLine(out, JsonOkReturn.build("connection_id", connectionId));
			return;
		}

		// Tests exceptions
		ServerSqlManager.testThrowException();

		// Redirect if it's a File download request (Blobs/Clobs)
		if (action.equals(HttpParameter.BLOB_DOWNLOAD)) {
			blobDownload(request, response, username, databaseConfigurator);
			return;
		}

		// No need to get a SQL connection for getting Blob size
		if (action.equals(HttpParameter.GET_BLOB_LENGTH)) {
			String blobId = request.getParameter(HttpParameter.BLOB_ID);
			long length = -1;

			File blobDirectory = databaseConfigurator.getBlobsDirectory(username);

			if (blobDirectory != null && !blobDirectory.exists()) {
				blobDirectory.mkdirs();
			}

			if (blobDirectory == null || !blobDirectory.exists()) {
				PrintWriter prinWriter = response.getWriter();
				JsonErrorReturn errorReturn = new JsonErrorReturn(response, HttpServletResponse.SC_NOT_FOUND,
						JsonErrorReturn.ERROR_ACEQL_ERROR,
						JsonErrorReturn.BLOB_DIRECTORY_DOES_NOT_EXIST + blobDirectory.getName());
				prinWriter.println(errorReturn.build());
				return;
			}

			try {
				length = BlobUtil.getBlobLength(blobId, blobDirectory);
			} catch (Exception e) {
				out = response.getOutputStream();
				JsonErrorReturn errorReturn = new JsonErrorReturn(response, HttpServletResponse.SC_NOT_FOUND,
						JsonErrorReturn.ERROR_ACEQL_ERROR, JsonErrorReturn.INVALID_BLOB_ID_DOWNLOAD + blobId);
				ServerSqlManager.writeLine(out, errorReturn.build());
				return;
			}

			out = response.getOutputStream();
			ServerSqlManager.writeLine(out, JsonOkReturn.build("length", length + ""));
			return;
		}

		debug("Before if (action.equals(HttpParameter.LOGOUT))");

		if (action.equals(HttpParameter.LOGOUT) || action.equals(HttpParameter.DISCONNECT)) {
			ServerLogout.logout(request, response, databaseConfigurator);
			return;
		}

		out = response.getOutputStream();
		if (action.equals(HttpParameter.GET_VERSION)) {
			String version = new org.kawanfw.sql.version.Version.PRODUCT().server();
			ServerSqlManager.writeLine(out, JsonOkReturn.build("result", version));
			return;
		}

		// Start clean Connections thread
		connectionStoreClean();

		Connection connection = null;

		try {
			ConnectionStore connectionStore = new ConnectionStore(username, sessionId, connectionId);

			// Hack to allow version 1.0 to continue to get connection
			if (connectionId == null || connectionId.isEmpty()) {
				connection = connectionStore.getFirst();
			} else {
				connection = connectionStore.get();
			}

			if (connection == null || connection.isClosed()) {
				JsonErrorReturn errorReturn = new JsonErrorReturn(response, HttpServletResponse.SC_NOT_FOUND,
						JsonErrorReturn.ERROR_ACEQL_ERROR, JsonErrorReturn.INVALID_CONNECTION);
				ServerSqlManager.writeLine(out, errorReturn.build());
				return;
			}

		} catch (SQLException e) {
			JsonErrorReturn jsonErrorReturn = new JsonErrorReturn(response, HttpServletResponse.SC_BAD_REQUEST,
					JsonErrorReturn.ERROR_ACEQL_ERROR, JsonErrorReturn.UNABLE_TO_GET_A_CONNECTION,
					ExceptionUtils.getStackTrace(e));
			ServerSqlManager.writeLine(out, jsonErrorReturn.build());
			LoggerUtil.log(request, e);

			return;
		}

		// Release connection in pool & remove all references
		if (action.equals(HttpParameter.CLOSE)) {
			try {
				// ConnectionCloser.freeConnection(connection,
				// databaseConfigurator);
				databaseConfigurator.close(connection);
				if (connectionId == null) {
					connectionId = ServerLoginActionSql.getConnectionId(connection);
				}
				ConnectionStore connectionStore = new ConnectionStore(username, sessionId, connectionId);
				connectionStore.remove();
				ServerSqlManager.writeLine(out, JsonOkReturn.build());
			} catch (SQLException e) {
				JsonErrorReturn errorReturn = new JsonErrorReturn(response, HttpServletResponse.SC_BAD_REQUEST,
						JsonErrorReturn.ERROR_JDBC_ERROR, e.getMessage());
				ServerSqlManager.writeLine(out, errorReturn.build());
			}
			return;
		}

		if (isStatement(action) && !isStoredProcedure(request)) {
			ServerStatement serverStatement = new ServerStatement(request, response, databaseConfigurator, connection);
			serverStatement.executeQueryOrUpdate(out);
		} else if (isStoredProcedure(request)) {
			ServerCallableStatement serverCallableStatement = new ServerCallableStatement(request, response,
					databaseConfigurator, connection);
			serverCallableStatement.executeOrExecuteQuery(out);
		} else if (isConnectionModifier(action)) {
			TransactionUtil.setConnectionModifierAction(request, response, out, action, connection);
		} else if (isSavepointModifier(action)) {
			SavepointUtil.setSavepointExecute(request, response, out, action, connection);
		} else if (isConnectionReader(action)) {
			TransactionUtil.getConnectionionInfosExecute(request, response, out, action, connection);
		} else {
			throw new IllegalArgumentException("Invalid Sql Action: " + action);
		}

	}

	private boolean isStoredProcedure(HttpServletRequest request) {
		String storedProcedure = request.getParameter(HttpParameter.STORED_PROCEDURE);
		String sql = request.getParameter(HttpParameter.SQL);
		boolean explicitStoredProcedure = Boolean.parseBoolean(storedProcedure);

		if (explicitStoredProcedure) {
			return true;
		}

		// From Python there maybe an implicit call without any more info
		boolean implicitStoredProcedure = false;

		if (sql != null) {
			sql = sql.trim().toLowerCase();
			if (sql.startsWith("{") && sql.endsWith("}") && sql.contains("call ")) {
				implicitStoredProcedure = true;
			}
		}

		if (implicitStoredProcedure) {
			return true;
		} else {
			return false;
		}

	}

	/**
	 * Clean connection store.
	 */
	private void connectionStoreClean() {

		if (ConnectionStoreCleaner.timeToCleanConnectionStore()) {
			ConnectionStoreCleaner cleaner = new ConnectionStoreCleaner();
			cleaner.start();
		}

	}

	private void blobUpload(HttpServletRequest request, HttpServletResponse response)
			throws IOException, FileUploadException, SQLException {
		debug("BlobUploadConfigurator Start");

		// Pass Username & Database because they can't be recovered from
		// stream. The underlying HttpServletRequest is a
		// HttpServletRequestHolder that stores parameters in map

		String username = request.getParameter(HttpParameter.USERNAME);
		String database = request.getParameter(HttpParameter.DATABASE);
		DatabaseConfigurator databaseConfigurator = ServerSqlManager.getDatabaseConfigurator(database);

		File blobDirectory = databaseConfigurator.getBlobsDirectory(username);

		if (blobDirectory != null && !blobDirectory.exists()) {
			blobDirectory.mkdirs();
		}

		if (blobDirectory == null || !blobDirectory.exists()) {
			PrintWriter out = response.getWriter();
			JsonErrorReturn errorReturn = new JsonErrorReturn(response, HttpServletResponse.SC_NOT_FOUND,
					JsonErrorReturn.ERROR_ACEQL_ERROR,
					JsonErrorReturn.BLOB_DIRECTORY_DOES_NOT_EXIST + blobDirectory.getName());
			out.println(errorReturn.build());
			return;
		}

		PrintWriter out = response.getWriter();
		try {
			BlobUploadConfigurator blobUploadConfigurator = ServerSqlManager.getBlobUploadConfigurator();
			blobUploadConfigurator.upload(request, response, blobDirectory);

			// Say it's OK to the client
			out.println(JsonOkReturn.build());
		} catch (Exception e) {

			JsonErrorReturn errorReturn = new JsonErrorReturn(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					JsonErrorReturn.ERROR_ACEQL_ERROR, JsonErrorReturn.ERROR_UPLOADING_BLOB + e.getMessage(),
					ExceptionUtils.getStackTrace(e));
			out.println(errorReturn.build());

			LoggerUtil.log(request, e);
		}
	}

	private void blobDownload(HttpServletRequest request, HttpServletResponse response, String username,
			DatabaseConfigurator databaseConfigurator) throws IOException, SQLException {
		String blobId = request.getParameter(HttpParameter.BLOB_ID);

		File blobDirectory = databaseConfigurator.getBlobsDirectory(username);

		if (blobDirectory != null && !blobDirectory.exists()) {
			blobDirectory.mkdirs();
		}

		if (blobDirectory == null || !blobDirectory.exists()) {
			PrintWriter prinWriter = response.getWriter();
			JsonErrorReturn errorReturn = new JsonErrorReturn(response, HttpServletResponse.SC_NOT_FOUND,
					JsonErrorReturn.ERROR_ACEQL_ERROR,
					JsonErrorReturn.BLOB_DIRECTORY_DOES_NOT_EXIST + blobDirectory.getName());
			prinWriter.println(errorReturn.build());
			return;
		}

		String fileName = databaseConfigurator.getBlobsDirectory(username).toString() + File.separator + blobId;

		File file = new File(fileName);

		if (!file.exists()) {
			PrintWriter prinWriter = response.getWriter();
			JsonErrorReturn errorReturn = new JsonErrorReturn(response, HttpServletResponse.SC_NOT_FOUND,
					JsonErrorReturn.ERROR_ACEQL_ERROR, JsonErrorReturn.INVALID_BLOB_ID_DOWNLOAD + blobId);
			prinWriter.println(errorReturn.build());
			return;
		}

		OutputStream out = response.getOutputStream();
		try {
			BlobDownloadConfigurator BlobDownloader = ServerSqlManager.getBlobDownloadConfigurator();
			BlobDownloader.download(request, file, out);
		} catch (Exception e) {
			JsonErrorReturn errorReturn = new JsonErrorReturn(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					JsonErrorReturn.ERROR_ACEQL_ERROR, JsonErrorReturn.ERROR_DOWNLOADING_BLOB + e.getMessage(),
					ExceptionUtils.getStackTrace(e));
			ServerSqlManager.writeLine(out, errorReturn.build());

			LoggerUtil.log(request, e);
		}
	}

	private boolean isSavepointModifier(String action) {

		if (action.equals(HttpParameter.SET_SAVEPOINT) || action.equals(HttpParameter.SET_SAVEPOINT_NAME)
				|| action.equals(HttpParameter.ROLLBACK_SAVEPOINT) || action.equals(HttpParameter.RELEASE_SAVEPOINT)) {
			return true;
		} else {
			return false;
		}

	}

	private boolean isConnectionModifier(String action) {
		if (action.equals(HttpParameter.SET_AUTO_COMMIT) || action.equals(HttpParameter.COMMIT)
				|| action.equals(HttpParameter.ROLLBACK) || action.equals(HttpParameter.SET_READ_ONLY)
				|| action.equals(HttpParameter.SET_HOLDABILITY)
				|| action.equals(HttpParameter.SET_TRANSACTION_ISOLATION_LEVEL) || action.equals(HttpParameter.CLOSE)) {
			return true;
		} else {
			return false;
		}
	}

	private boolean isConnectionReader(String action) {
		if (action.equals(HttpParameter.GET_AUTO_COMMIT) || action.equals(HttpParameter.GET_CATALOG)
				|| action.equals(HttpParameter.GET_HOLDABILITY) || action.equals(HttpParameter.IS_READ_ONLY)
				|| action.equals(HttpParameter.GET_TRANSACTION_ISOLATION_LEVEL)) {
			return true;
		} else {
			return false;
		}
	}

	private boolean isStatement(String action) {
		if (action.equals(HttpParameter.EXECUTE_UPDATE) || action.equals(HttpParameter.EXECUTE_QUERY)) {
			return true;
		} else {
			return false;
		}

	}

	/**
	 * Says if an Action asked by the client is for Awake FILE
	 * 
	 * @param action
	 *            the action asked by the client side
	 * @return true if the action is for Awake FILE
	 */
	@SuppressWarnings("unused")
	private boolean isActionForBlob(String action) {
		if (action.equals(HttpParameter.BLOB_UPLOAD) || action.equals(HttpParameter.BLOB_DOWNLOAD)) {
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Method called by children Servlet for debug purpose Println is done only if
	 * class name name is in kawansoft-debug.ini
	 */
	public static void debug(String s) {
		if (DEBUG) {
			System.out.println(new Date() + " " + s);
		}
	}
}
