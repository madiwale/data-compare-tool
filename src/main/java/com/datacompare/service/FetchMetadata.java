
/**
 * Service class to Fatch the metadata of the database tables for multiple databases( source and target)
 *
 *
 * @author      Harnath Valeti
 * @author      Madhu Athinarapu
 * @version     1.0
 * @since       1.0
 */

package com.datacompare.service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datacompare.model.AppProperties;
import com.datacompare.model.DatabaseInfo;
import com.datacompare.model.TableColumnMetadata;
import com.datacompare.util.JdbcUtil;

public class FetchMetadata {

	/** */
	public Logger logger = LoggerFactory.getLogger("FetchMetadataLog");

	private List<String> chunks = new ArrayList<String>();

	public List<String> sortColumns = new ArrayList<String>();

	public List<String> columns = new ArrayList<String>();

	private String dbType = null;

	private int fetchSize;

	private int maxDecimals;
	
	private int maxTextSize;
	
	private long rowCount;
	
	private boolean hasNoUniqueKey; 
	
	private String sortKey;
	
	private String primaryKey;

	private String sql = null;

	private Map<String, TableColumnMetadata> tableMetadataMap = new LinkedHashMap<String, TableColumnMetadata>();

	/**
	 * Function reading the table meta data checking the each columns value
	 * 
	 * @param dbType
	 * @param sourceDBType
	 * @param connection
	 * @param schemaName
	 * @param tableName
	 * @param rowCount
	 * @param sourceSortKey
	 * @param sourcePrimaryKey
	 * @param sourceTableMetadataMap
	 * @param columnList
	 * @param appProperties
	 * @throws Exception
	 */
	public FetchMetadata(String dbType, String sourceDBType, Connection connection, String schemaName, String tableName,
			long rowCount, String sourceSortKey, String sourcePrimaryKey, boolean sourceHasNoUniqueKey,
			Map<String, TableColumnMetadata> sourceTableMetadataMap, List<String> columnList,
			AppProperties appProperties) throws Exception {
		
		if (!EnumUtils.isValidEnum(DatabaseInfo.dbType.class, dbType)) throw new Exception(dbType + " not supported.");
		
		if("Detail".equals(appProperties.getReportType())) {
			
			fetchDetailData(dbType, sourceDBType, connection, schemaName, tableName, rowCount, sourceSortKey,
					sourcePrimaryKey, sourceHasNoUniqueKey, sourceTableMetadataMap, columnList, appProperties);
			
		} else if("Basic".equals(appProperties.getReportType())) {
			
			fetchBasicData(connection, schemaName, tableName, appProperties);
		}
	}
	
	/**
	 * 
	 * @param dbType
	 * @param sourceDBType
	 * @param connection
	 * @param schemaName
	 * @param tableName
	 * @param rowCount
	 * @param sourceSortKey
	 * @param sourcePrimaryKey
	 * @param sourceHasNoUniqueKey
	 * @param sourceTableMetadataMap
	 * @param columnList
	 * @param appProperties
	 * @throws Exception
	 */
	private void fetchDetailData(String dbType, String sourceDBType, Connection connection, String schemaName,
			String tableName, long rowCount, String sourceSortKey, String sourcePrimaryKey, boolean sourceHasNoUniqueKey,
			Map<String, TableColumnMetadata> sourceTableMetadataMap, List<String> columnList,
			AppProperties appProperties) throws Exception {
		
		StringBuilder sortKey = new StringBuilder();
		StringBuilder primaryKey = new StringBuilder();
		StringBuilder uniqueKeyCol = new StringBuilder();

		setDbType(dbType);
		setFetchSize(appProperties.getFetchSize());
		setMaxDecimals(appProperties.getMaxDecimals());
		setMaxTextSize(appProperties.getMaxTextSize()); 

		Map<Integer, String> primaryKeyMap = new TreeMap<Integer, String>();

		fetchTableColumns(sourceDBType, connection, schemaName, tableName, columnList, appProperties.isIgnoreColumns()); 
		fetchPrimaryColumns(connection, schemaName, tableName, primaryKeyMap, sourceSortKey, sourcePrimaryKey,
				sourceHasNoUniqueKey, sortKey, primaryKey, uniqueKeyCol);
		prepareQuery(connection, schemaName, tableName, sortKey.toString(), primaryKey.toString(),
				uniqueKeyCol.toString(), appProperties.getFilter(), appProperties.getFilterType(), rowCount, sourceTableMetadataMap);
	}
	
	/**
	 * 
	 * @param connection
	 * @param schemaName
	 * @param tableName
	 * @param appProperties
	 * @throws SQLException
	 */
	private void fetchBasicData(Connection connection, String schemaName, String tableName,
			AppProperties appProperties) throws SQLException { 
		
		Long totalRecords = getTotalRecords(connection, schemaName, tableName, appProperties.getFilter()); 

		setRowCount(totalRecords);
	}

	/**
	 * 
	 * @param connection
	 * @param schemaName
	 * @param tableName
	 * @param sortKey
	 * @param primaryKey
	 * @param uniqueKeyCol
	 * @param filter
	 * @param filterType
	 * @param rowCount
	 * @param sourceTableMetadataMap
	 * @throws SQLException
	 */
	private void prepareQuery(Connection connection, String schemaName, String tableName, String sortKey,
			String primaryKey, String uniqueKeyCol, String filter, String filterType, long rowCount,
			Map<String, TableColumnMetadata> sourceTableMetadataMap) throws SQLException {
		
		String query = null;
		String sortCols = null;
		String cols = null;
		
		switch (getDbType()) {

		case "POSTGRESQL":

			sortCols = replaceColumnWithHash(getTargetColumns(sourceTableMetadataMap,true));

			cols = replaceColumnWithHash(getTargetColumns(sourceTableMetadataMap,false));
			uniqueKeyCol=replaceColumnWithHash(uniqueKeyCol);
			
			query = "SELECT " + uniqueKeyCol + cols + " FROM " + schemaName + "." + tableName;


			if(isHasNoUniqueKey()) {
				
				String subQuery = "SELECT " + uniqueKeyCol + cols + " FROM (" + "SELECT " + cols + " FROM " + schemaName + "." + tableName + " order by " + sortCols + ") t1";
				
				query = "SELECT t2.* FROM (" + subQuery + ") t2";
			}
			
			generateChunksPostgresql(connection, schemaName, tableName, rowCount, filter);

			break;

		case "ORACLE":

			sortCols = getSourceColumns(true);
			cols=getSourceColumns(false);
			query = "SELECT " + uniqueKeyCol + cols + " FROM " + schemaName + "." + tableName;
			
			if(isHasNoUniqueKey()) {
				
				String subQuery = "SELECT " + uniqueKeyCol + cols + " FROM (" + "SELECT " + cols + " FROM " + schemaName + "." + tableName  + " order by " + sortCols + ")";
				
				query = "SELECT * FROM (" + subQuery +  ")";
			}
			
			generateSourceChunks(connection, schemaName, tableName, sortKey, primaryKey, filter, filterType, sortCols);

			break;

		case "SQLSERVER":

			cols = getSourceColumns(false);
			sortCols=getSourceColumns(true);
			query = "SELECT " + uniqueKeyCol + cols + " FROM " + schemaName + "." + tableName;
			//TODO
			if(isHasNoUniqueKey()) {
				String subQuery = "SELECT " + uniqueKeyCol + cols + " FROM (" + "SELECT " +  cols + " FROM " + schemaName + "." + tableName + ") t1";
				query = "SELECT t2.* FROM (" + subQuery + " order by " + sortCols + ") t2";
			}
			generateSourceChunks(connection, schemaName, tableName, sortKey, primaryKey, filter, filterType, sortCols);
			break;
		}
		
		setSql(query); 
		
		logger.info("\n" + getDbType() + ": SQL Query without chunk: " + getSql());
	}

	private String replaceColumnWithHash(String targetColumns) {

		if(targetColumns!=null && !targetColumns.isEmpty()) {
			String cols[] = targetColumns.split(",");
			if (cols != null && cols.length > 0) {
				for (int i = 0; i < cols.length; i++) {
					if (cols[i].contains("#")) {
						String replaceString = "\"" + cols[i] + "\"";
						targetColumns=	targetColumns.replace(cols[i], replaceString);
					}
				}
			}
		}
		return targetColumns;
	}

	/**
	 * 
	 * @return
	 */
	private String getSourceColumns(boolean isSortColumn) {
		
		return getColumns(getTableMetadataMap(), getTableMetadataMap(), false, isSortColumn);
	}
	
	/**
	 * 
	 * @param sourceTableMetadataMap
	 * @return
	 */
	private String getTargetColumns(Map<String, TableColumnMetadata> sourceTableMetadataMap, boolean isSortColumn) {
		
		return getColumns(sourceTableMetadataMap, getTableMetadataMap(), true,isSortColumn);
	}

	/**
	 * 
	 * @param sourceTableMetadataMap
	 * @param targetTableMetadataMap
	 * @param isTarget
	 * @return
	 */
	private String getColumns(Map<String, TableColumnMetadata> sourceTableMetadataMap,
			Map<String, TableColumnMetadata> targetTableMetadataMap, boolean isTarget, boolean isSortColumn) {
		
		StringBuilder cols = new StringBuilder();
		List<String> colNames = new ArrayList<String>(sourceTableMetadataMap.keySet());
		
		for (String colName : colNames) {
		
			colName = isTarget ? colName.toLowerCase() : colName.toUpperCase();
			
			String colAs = targetTableMetadataMap.get(colName) !=null ? targetTableMetadataMap.get(colName).getColumnAs():"";
			String columnType = targetTableMetadataMap.get(colName)!=null ?targetTableMetadataMap.get(colName).getColumnType():"";
			int colSize = targetTableMetadataMap.get(colName)!=null ? targetTableMetadataMap.get(colName).getColSize():0;
             /** seperating the sorting columns from select column to avoid sql issues for CLOB/BLOB
             * 1. Created two set of columns
			  * 2. Excluing from the columns for sorting
             * */
			if(isSortColumn && (binaryColumnType(columnType) || varcharLargeSize(columnType, colSize))) {
				columns.add(colName);
			}else {
				sortColumns.add(colName);
				columns.add(colName);
				cols.append(colAs + ",");
			}
		}
		if (!cols.toString().isEmpty()) {

			cols.deleteCharAt(cols.length() - 1);
		}
		
		return cols.toString();
	}
	
	/**
	 * 
	 * @param connection
	 * @param schemaName
	 * @param tableName
	 * @param primaryKeyMap
	 * @param sourceSortKey
	 * @param sourcePrimaryKey
	 * @param sourceHasNoUniqueKey
	 * @param sortKey
	 * @param primaryKey
	 * @param uniqueKeyCol
	 * @throws Exception
	 */
	private void fetchPrimaryColumns(Connection connection, String schemaName, String tableName,
			Map<Integer, String> primaryKeyMap, String sourceSortKey, String sourcePrimaryKey, boolean sourceHasNoUniqueKey, StringBuilder sortKey,
			StringBuilder primaryKey, StringBuilder uniqueKeyCol) throws Exception {
		
		if(!sourceHasNoUniqueKey) {
			
			ResultSet rs = null;
			
			try {

				rs = connection.getMetaData().getPrimaryKeys(null, schemaName, tableName);

				while (rs.next()) {

					String colName = rs.getString("COLUMN_NAME");
					Integer pkPosition = rs.getInt("KEY_SEQ");
					primaryKeyMap.put(pkPosition, colName);
				}
				
				if (sourceSortKey != null && sourceSortKey.trim().length() > 0) {

					sortKey.append(sourceSortKey.toLowerCase());
					primaryKey.append(sourcePrimaryKey.toLowerCase());

				} else if (primaryKeyMap != null && primaryKeyMap.size() > 0) {

					sortKey.append(StringUtils.join(primaryKeyMap.values(),","));
					primaryKey.append(primaryKeyMap.get(1));

					this.setSortKey(sortKey.toString());
					this.setPrimaryKey(primaryKey.toString()); 
				}

			} catch (Exception e) {

				logger.error(getDbType(), e);

			} finally {

				new JdbcUtil().closeResultSet(rs);
			}
		}

		if (sortKey.toString().isEmpty()) {

			setHasNoUniqueKey(true); 
			//throw new Exception(getDbType() + " Database Table " + schemaName + "." + tableName + " PRIMARY Key not found.");
		}

		if (!sortKey.toString().isEmpty() && sortKey.toString().contains(",")) {

			String primaryKeyColsTemp = sortKey.toString().replaceAll(",", "),").replaceFirst("\\)\\,", ",") + ")";

			String primaryKeyColsTempArray[] = primaryKeyColsTemp.split("\\)\\,");

			for (int i = 0; i < primaryKeyColsTempArray.length; i++) {

				primaryKeyColsTemp = "concat(" + convertDateToTimeStamp(primaryKeyColsTemp);
			}

			uniqueKeyCol.append(primaryKeyColsTemp + " AS key1 ,");

		} else if(!primaryKey.toString().isEmpty()) {

			uniqueKeyCol.append(primaryKey + " AS key1 ,");
			
		} else if(isHasNoUniqueKey()) {
			
			if("ORACLE".equals(getDbType())) {
				
				uniqueKeyCol.append("ROWNUM AS key1,");
				
			} else if("POSTGRESQL".equals(getDbType())) {
				
				uniqueKeyCol.append("row_number() over() as key1,");
				
			} else if("SQLSERVER".equals(getDbType())) {
				
				//TODO
				uniqueKeyCol.append("ROWNUM AS key1,");
			}
		}
	}

	public String convertDateToTimeStamp(String colName) {

		String colNameTmp = colName;
		if (colNameTmp != null) {
			String cols[] = colNameTmp.replace(")", "").split(",");
			for (int i = 0; i < cols.length; i++) {
				if (tableMetadataMap != null && tableMetadataMap.get(cols[i]) != null) {
					String colType = tableMetadataMap.get(cols[i]).getColumnType();
					if (colType != null && (colType.contains("DATE") || colType.contains("date") || colType.contains("TIMESTAMP") || colType.contains("timestamp"))) {
						colName=colName.replace(cols[i], "TO_CHAR(" + cols[i] + ", \'YYYY-MM-DD HH24:MI:SS\')");
					}
				}
			}
		}
		return colName;
	}
	/**
	 * 
	 * @param sourceDBType
	 * @param connection
	 * @param schemaName
	 * @param tableName
	 * @param columnList
	 */
	private void fetchTableColumns(String sourceDBType, Connection connection, String schemaName,
			String tableName, List<String> columnList, boolean ignoreColumns) {
		
		ResultSet rs = null;
		
		try {

			rs = connection.getMetaData().getColumns(null, schemaName, tableName, null);
			rs.setFetchSize(getFetchSize());

			while (rs.next()) {
				
				String col = null;

				String columnType = rs.getString("TYPE_NAME");

				String columnName = rs.getString("COLUMN_NAME");
				
				String columnSize = rs.getString("COLUMN_SIZE");

				int colSize = (columnSize != null && !columnSize.equals("null")) ? Integer.parseInt(columnSize) : 0;

				if(column(columnList, columnName, ignoreColumns)) continue;
				
				if (binaryColumnType(columnType) || varcharLargeSize(columnType, colSize)) {/*
					
					*//** In case if source db is Oracle, it will check length for binary column types.
					 *  If source db is SQLServer, it will check MD5 hash string for binary column types.*//*
					if("ORACLE".equals(getDbType())) {
						
						if(varcharLargeSize(columnType, colSize)) {   
							
							col = "LOWER(STANDARD_HASH(NVL(" + columnName
									+ ",'NULL'), 'MD5')) AS " + columnName;
						} else {
							
							col = "DBMS_LOB.GETLENGTH(" + columnName + ") AS " + columnName;
						}
						
					} else if("POSTGRESQL".equals(getDbType()) && "ORACLE".equals(sourceDBType) && !varcharLargeSize(columnType, colSize)) { 
						
						col = "LENGTH(" + columnName + ") as " + columnName;
						
					} else if("SQLSERVER".equals(getDbType())) {
						
						col = "HashBytes('MD5', COALESCE(" + columnName + ",'NULL')) as " + columnName;
						
					} else if("POSTGRESQL".equals(getDbType()) && ("SQLSERVER".equals(sourceDBType) || varcharLargeSize(columnType, colSize))) { 
						
						col = "MD5(COALESCE (" + columnName + ",'NULL')) as " + columnName;
						
					} else {
					}*/
					col = columnName;
					//col = "LENGTH(" + columnName + ") as " + columnName;
				} else {
					
					col = columnName;
				}

				boolean isNullable = rs.getString("IS_NULLABLE").toUpperCase().equals("YES");

				String decimalDigits = rs.getString("DECIMAL_DIGITS");

				int noOfDecimals = (decimalDigits != null && !decimalDigits.equals("null"))
						? Integer.parseInt(decimalDigits)
						: 0;

				String decimalFormat = "";
				
				if (columnType.compareTo("DECIMAL") == 0 || columnType.compareTo("NUMBER") == 0
						|| columnType.compareTo("numeric") == 0 || columnType.contains("float")
						|| columnType.contains("FLOAT") || columnType.compareTo("DOUBLE") == 0) {

					for (int i = 0; i < colSize; i++) {

						decimalFormat = decimalFormat + "#";
					}
				}
				
				if (noOfDecimals > getMaxDecimals() && getMaxDecimals() > 0) {
					
					decimalFormat = (decimalFormat.trim().length() > 0) ? decimalFormat + "." : "#.";

					for (int j = 0; j < getMaxDecimals(); j++) {

						decimalFormat = decimalFormat + "0";
					}
				} else if (noOfDecimals > 0) {
					
					decimalFormat = (decimalFormat.trim().length() > 0) ? decimalFormat + "." : "#.";

					for (int j = 0; j < noOfDecimals; j++) {

						decimalFormat = decimalFormat + "0";
					}
				}

				decimalFormat = decimalFormat.replace("#.", "0.");

				TableColumnMetadata tableColumnMetadata = new TableColumnMetadata();

				tableColumnMetadata.setColumnType(columnType);
				tableColumnMetadata.setColumnName(columnName);
				tableColumnMetadata.setNullable(isNullable);
				tableColumnMetadata.setNoOfDecimals(noOfDecimals);
				tableColumnMetadata.setColSize(colSize);
				tableColumnMetadata.setDecimalFormat(decimalFormat);
				tableColumnMetadata.setColumnAs(col); 
				tableColumnMetadata.setMaxTextSize(getMaxTextSize());

				tableMetadataMap.put(columnName, tableColumnMetadata);
			}

			if (tableMetadataMap.isEmpty()) {

				throw new Exception(getDbType() + " Database Table " + schemaName + "." + tableName + " has no columns.");
			}

		} catch (Exception e) {

			logger.error(getDbType(), e);

		} finally {

			new JdbcUtil().closeResultSet(rs);
		}
	}
	
	/**
	 * 
	 * @param connection
	 * @param schemaName
	 * @param tableName
	 * @param sortKey
	 * @param primaryKey
	 * @param filter
	 * @param filterType
	 * @param cols
	 * @throws SQLException
	 */
	private void generateSourceChunks(Connection connection, String schemaName, String tableName, String sortKey,
			String primaryKey, String filter, String filterType, String cols) throws SQLException {

		logger.info("Started preparing chunks");

		chunks.clear();
		
		Long totalRecords = getTotalRecords(connection, schemaName, tableName, filter); 

		setRowCount(totalRecords);

		long ntileSize = (long)Math.ceil(totalRecords / getFetchSize());
		
		ntileSize = (ntileSize <= 0) ? 1 : ntileSize;
		
		StringBuilder sql = new StringBuilder();
		
		if(isHasNoUniqueKey()) {
			
			sql.append("SELECT min(ROWNUM) AS startRange, max(ROWNUM) AS endRange,count(*) AS chunkSize, nt FROM (SELECT ROWNUM")
			.append(" ,ntile(").append(ntileSize).append(") OVER (ORDER BY ").append(cols).append(" ) nt FROM ").append(schemaName)
			.append(".").append(tableName);
			
		} else {

			sql.append("SELECT min(").append(primaryKey).append(") AS startRange, max(").append(primaryKey)
					.append(") AS endRange,count(*) AS chunkSize, nt FROM (SELECT ").append(primaryKey).append(" ,ntile(")
					.append(ntileSize).append(") OVER (ORDER BY ").append(primaryKey).append(" ) nt FROM ").append(schemaName)
					.append(".").append(tableName);
		}
			/*if(filter != null && !filter.isEmpty()) {
				
				sql.append(" WHERE ").append(filter);
			}*/

		
		if("ORACLE".equals(getDbType())) {
			
			sql.append(") GROUP BY nt ORDER BY nt");
			
		} else {
			
			sql.append(") as a GROUP BY nt ORDER BY nt");
		}
		
		logger.info("Fetch Chunks SQL Query: " + sql.toString()); 

		Statement stmt = connection.createStatement();

		ResultSet rs = stmt.executeQuery(sql.toString());
        int count=0;
		while (rs.next()) {

			int columnType= rs.getMetaData().getColumnType(1);

			long startRange=0;
			long endRange=0;
			if(!isNoNumericColumnType(columnType)){
				 startRange = rs.getLong("startRange");
				 endRange = rs.getLong("endRange");
			}
			long chunkSize = rs.getLong("chunkSize");

			StringBuilder condition = new StringBuilder();
            boolean whereapplied=false;
			boolean filterapplied=false;

			if(!isNoNumericColumnType(columnType)) {
				condition.append("where ");
				whereapplied=true;
			}

			if (filter != null && !filter.isEmpty() && !"Sample".equals(filterType)) {
			 if(!whereapplied) {
				 condition.append("where ");
				 whereapplied=true;
			 }
				condition.append(filter);
				filterapplied=true;
			}
			if(isHasNoUniqueKey()) {

				if (isNoNumericColumnType(columnType) ){
					condition.append(" order by 1");
				} else {

					if(filterapplied)
					condition.append(" and ");
                    if(count==0) {
						condition.append("key1").append(" >= ").append(startRange).append(" and ").append("key1")
								.append(" <= ").append(endRange).append(" order by 1");
					}
					else{
						condition.append("key1").append(" > ").append(startRange).append(" and ").append("key1")
								.append(" <= ").append(endRange).append(" order by 1");

					}
				}
			} else {

				if (isNoNumericColumnType(columnType) ){
					condition.append(" order by ").append(sortKey);
				} else {

					if(filterapplied)
						condition.append(" and ");
					if(count==0) {
						condition.append(primaryKey).append(" >=").append(startRange).append(" and ").append(primaryKey)
								.append(" <= ").append(endRange).append(" order by ").append(sortKey);
					}else{
						condition.append(primaryKey).append(" > ").append(startRange).append(" and ").append(primaryKey)
								.append(" <= ").append(endRange).append(" order by ").append(sortKey);
					}
				}
			}

			logger.debug("Chunk Range, Min: " + startRange + ", Max: " + endRange + ", Size: " + chunkSize); 
			
			chunks.add(condition.toString());
			count++;
		}
		
		JdbcUtil jdbcUtil = new JdbcUtil();
		
		jdbcUtil.closeResultSet(rs);
		jdbcUtil.closeStatement(stmt); 

		logger.info("Completed preparing chunks");
	}
	
	/**
	 * 
	 * @param connection
	 * @param schemaName
	 * @param tableName
	 * @param filter
	 * @return
	 * @throws SQLException
	 */
	private Long getTotalRecords(Connection connection, String schemaName, String tableName,
			String filter) throws SQLException {
		
		Long totalRecords = Long.valueOf(0); 

		StringBuilder sql = new StringBuilder();


		sql.append("SELECT count(*) as totalrec FROM ").append(schemaName).append(".").append(tableName);
		
		if(filter != null && filter.trim().length() > 0) {
			
			sql.append(" WHERE ").append(filter);
		}

		Statement stmt = connection.createStatement();
		ResultSet rs = stmt.executeQuery(sql.toString());

		if (rs.next()) {
			
			totalRecords = rs.getLong("totalrec");
		}

		JdbcUtil jdbcUtil = new JdbcUtil();
		
		jdbcUtil.closeResultSet(rs);
		jdbcUtil.closeStatement(stmt); 
		
		
		return totalRecords;
	}

	/**
	 * 
	 * @param connection
	 * @param schemaName
	 * @param tableName
	 * @param rowCount
	 * @param filter
	 * @throws SQLException
	 */
	private void generateChunksPostgresql(Connection connection, String schemaName, String tableName, long rowCount,
			String filter) throws SQLException {

		logger.info("Started preparing chunks for Postgresql");

		chunks.clear();

		//Long totalRecords = getTotalRecords(connection, schemaName, tableName, filter); 

		if (rowCount == 0) {

			throw new SQLException("There is no records in Postgresql for " + schemaName + "." + tableName);
		}

//		if (totalRecords == 0) {
//
//			throw new SQLException("There is no records in Postgresql for " + schemaName + "." + tableName);
//		}

//		setRowCount(totalRecords);
		setRowCount(0);

		logger.info("Completed preparing chunks for Postgresql");
	}

	/**
	 * 
	 * @param columnType
	 * @return
	 */
	private boolean binaryColumnType(String columnType) {

		return (columnType.contains("LOB") || columnType.contains("lob") || columnType.contains("BLOB")
				|| columnType.contains("blob") || columnType.contains("bytea") || columnType.contains("CLOB")
				|| columnType.contains("clob") || columnType.contains("TEXT") || columnType.contains("text"));
	}

	private boolean isNoNumericColumnType(int columnType) {
		return (columnType==1 || columnType==12 || columnType==91||columnType==92|| columnType==93 );
	}
	/**
	 * 
	 * @param columnType
	 * @param colSize
	 * @return
	 */
	private boolean varcharLargeSize(String columnType, int colSize) {
	
		return ( (columnType.contains("VARCHAR") || columnType.contains("varchar")) && colSize > getMaxTextSize());
	}

	/**
	 * 
	 * @param columnsList
	 * @param column
	 * @param ignoreColumn
	 * @return
	 */
	private boolean column(List<String> columnsList, String column, boolean ignoreColumn) {

		for (String col : columnsList) {
			
			if (col.equalsIgnoreCase(column)) {

				return ignoreColumn;
			}
	    }
		
	    return (!columnsList.isEmpty()) ? !ignoreColumn : false;	
	}

	/**
	 * 
	 * @return
	 */
	public List<String> getChunks() {
		return chunks;
	}

	/**
	 * 
	 * @return
	 */
	public String getDbType() {
		return dbType;
	}

	/**
	 * @return the fetchSize
	 */
	public int getFetchSize() {
		return fetchSize;
	}

	/**
	 * @return the maxDecimals
	 */
	public int getMaxDecimals() {
		return maxDecimals;
	}

	/**
	 * 
	 * @return
	 */
	public long getRowCount() {
		return rowCount;
	}

	/**
	 * 
	 * @return
	 */
	public String getSortKey() {
		return sortKey;
	}

	/**
	 * @return the sql
	 */
	public String getSql() {
		return sql;
	}

	/**
	 * 
	 * @param dbType
	 */
	public void setDbType(String dbType) {
		this.dbType = dbType;
	}

	/**
	 * @param fetchSize the fetchSize to set
	 */
	public void setFetchSize(int fetchSize) {
		this.fetchSize = fetchSize;
	}

	/**
	 * @param maxDecimals the maxDecimals to set
	 */
	public void setMaxDecimals(int maxDecimals) {
		this.maxDecimals = maxDecimals;
	}

	/**
	 * 
	 * @param rowCount
	 */
	public void setRowCount(long rowCount) {
		this.rowCount = rowCount;
	}

	/**
	 * 
	 * @param sortKey
	 */
	public void setSortKey(String sortKey) {
		this.sortKey = sortKey;
	}

	/**
	 * @param sql the sql to set
	 */
	public void setSql(String sql) {
		this.sql = sql;
	}

	/**
	 * @return the tableMetadataMap
	 */
	public Map<String, TableColumnMetadata> getTableMetadataMap() {
		return tableMetadataMap;
	}

	/**
	 * @param tableMetadataMap the tableMetadataMap to set
	 */
	public void setTableMetadataMap(Map<String, TableColumnMetadata> tableMetadataMap) {
		this.tableMetadataMap = tableMetadataMap;
	}

	public String getPrimaryKey() {
		return primaryKey;
	}

	public void setPrimaryKey(String primaryKey) {
		this.primaryKey = primaryKey;
	}

	/**
	 * @return the hasNoUniqueKey
	 */
	public boolean isHasNoUniqueKey() {
		return hasNoUniqueKey;
	}

	/**
	 * @param hasNoUniqueKey the hasNoUniqueKey to set
	 */
	public void setHasNoUniqueKey(boolean hasNoUniqueKey) {
		this.hasNoUniqueKey = hasNoUniqueKey;
	}

	/**
	 * @return the maxTextSize
	 */
	public int getMaxTextSize() {
		return maxTextSize;
	}

	/**
	 * @param maxTextSize the maxTextSize to set
	 */
	public void setMaxTextSize(int maxTextSize) {
		this.maxTextSize = maxTextSize;
	}
}