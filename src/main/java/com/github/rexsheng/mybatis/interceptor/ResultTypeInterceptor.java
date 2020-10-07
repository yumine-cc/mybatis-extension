package com.github.rexsheng.mybatis.interceptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.MappedStatement.Builder;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.rexsheng.mybatis.config.BuilderConfiguration;
import com.github.rexsheng.mybatis.core.MappedStatementFactory;
import com.github.rexsheng.mybatis.core.PagedList;

/**
 * @author RexSheng
 * 2020年8月28日 下午4:26:40
 */
@Intercepts({@Signature(
        type = Executor.class,
        method = "query",
        args = {
            MappedStatement.class, 
            Object.class, 
            RowBounds.class, 
            ResultHandler.class
        }
    ),
		@Signature(
		        type = Executor.class,
		        method = "update",
		        args = {
		            MappedStatement.class, 
		            Object.class
		        }
		    ),
//		@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})
}
)
public class ResultTypeInterceptor implements Interceptor{

	private Logger logger=LoggerFactory.getLogger(ResultTypeInterceptor.class); 
	
	private BuilderConfiguration builderConfig=new BuilderConfiguration();
	
	@SuppressWarnings("unchecked")
	@Override
	public Object intercept(Invocation invocation) throws Throwable {
		final Object[] args = invocation.getArgs();
	    MappedStatement ms = (MappedStatement) args[0];
	    String methodName=ms.getId();
	    if(methodName!=null) {
	    	if(methodName.contains("com.github.rexsheng.mybatis.mapper.DynamicMapper.selectByBuilder")) {
	    		Object parameterObject = args[1];
	    		
	    		com.github.rexsheng.mybatis.extension.QueryBuilder<?> queryBuilder=(com.github.rexsheng.mybatis.extension.QueryBuilder<?>)parameterObject;
            	queryBuilder.setBuiderConfig(builderConfig);
            	Boolean ifCalculateTotal=queryBuilder.getTable().getTotalCountEnabled();
            	BoundSql boundSql = ms.getSqlSource().getBoundSql(parameterObject);
            	if(ifCalculateTotal) {
            		String countSql="select count(*) from ("+boundSql.getSql()+") a";
                	Connection conn =ms.getConfiguration().getEnvironment().getDataSource().getConnection();
                	PreparedStatement countStatement = null;
                	ResultSet rs=null;
                    long totalItemCount = 0;
                    try {
                    	//预编译统计总记录数的sql
                    	countStatement = conn.prepareStatement(countSql);
                    	
                        Object params = boundSql.getParameterObject();

                        BoundSql countBs=copyAndNewBS(ms,boundSql,countSql);
        				//当sql带有参数时，下面的这句话就是获取查询条件的参数 
        				DefaultParameterHandler parameterHandler = new DefaultParameterHandler(ms,params,countBs);
        				//经过set方法，就可以正确的执行sql语句  
        				parameterHandler.setParameters(countStatement);  
                        //执行查询语句
                        rs = countStatement.executeQuery();
                        while (rs.next()) {	                    	
                        	totalItemCount = rs.getInt(1);
                        	logger.debug("count result:{},sql:{}",totalItemCount,countSql);
                        }
                    } catch (SQLException e) {
                        e.printStackTrace();
                    } finally {
                    	try {
                			if(rs!=null) {
                				rs.close();
                			}
                			if(countStatement!=null) {
                				countStatement.close();
                			}
                			if(conn!=null) {
                				conn.close();
                			}
                        } catch (SQLException e) {
                            e.printStackTrace();
                        }
                    }
                    queryBuilder.getTable().setTotalItemCount(totalItemCount);
            	}
            	
            	String additionalSql="";
				List<ParameterMapping> newParamterMappings=boundSql.getParameterMappings()==null?new ArrayList<>():new ArrayList<>(boundSql.getParameterMappings());
				switch(builderConfig.getDbType().toLowerCase()) {
			    	case "mysql":
						if(queryBuilder.getTable().getPageSize()!=null) {
							additionalSql+=" LIMIT ?";
							newParamterMappings.add(createNewParameterMapping(ms,"table.pageSize",java.lang.Integer.class));
						}
						if(queryBuilder.getTable().getSkipSize()!=null) {
							additionalSql+=" OFFSET ?";
							newParamterMappings.add(createNewParameterMapping(ms,"table.skipSize",java.lang.Integer.class));
						}
						break;
					default:
						break;
				}
				BoundSql newBoundSql=new BoundSql(ms.getConfiguration(),boundSql.getSql()+additionalSql
						,newParamterMappings,boundSql.getParameterObject());
				//复制ms，重设类型
			    args[0] = MappedStatementFactory.changeMappedStatementResultType(ms,new SonOfSqlSource(newBoundSql), queryBuilder.getOutputClazz());
	    	}
	    	else if(methodName.equalsIgnoreCase("com.github.rexsheng.mybatis.mapper.DynamicMapper.selectBySql")) {
	    		Object parameterObject = args[1];
			    //获取参数中设置的返回值类型
	    		Class<?> resultType = getResultType(parameterObject,"arg1");
			    if(resultType == null){
			        return invocation.proceed();
			    }
			    //复制ms，重设类型
			    args[0] = MappedStatementFactory.changeMappedStatementResultType(ms, resultType);
	    	}
	    	else if(methodName.equalsIgnoreCase("com.github.rexsheng.mybatis.mapper.DynamicMapper.selectBySqlWithParams") ||
	    			methodName.equalsIgnoreCase("com.github.rexsheng.mybatis.mapper.DynamicMapper.selectByMapWithParams")) {
	    		Object parameterObject = args[1];
			    //获取参数中设置的返回值类型
	    		Class<?> resultType = methodName.equalsIgnoreCase("com.github.rexsheng.mybatis.mapper.DynamicMapper.selectBySqlWithParams")?getResultType(parameterObject,"clazz"):Map.class;
			    if(resultType == null){
			        return invocation.proceed();
			    }
			    //获取参数中设置的返回值类型
	    		String sql=(String)getResultValue(parameterObject,"sql");
	    		Map<String,Object> paramMap=(Map<String,Object>)getResultValue(parameterObject,"params");
	    		logger.debug("interceptor sql:{}",sql);
	    		BoundSql boundSql=ms.getBoundSql(parameterObject);
	    		
	    		BoundSql newBoundSql=new BoundSql(ms.getConfiguration(),boundSql.getSql(),boundSql.getParameterMappings()==null?new ArrayList<>():new ArrayList<>(boundSql.getParameterMappings()),boundSql.getParameterObject());
	    		int i=1;
	    		Pattern pattern=Pattern.compile("#\\{\\w+\\}");
				Matcher matcher=pattern.matcher(sql);
				while(matcher.find()) {
					String variable=matcher.group().substring(2, matcher.group().length()-1);
					Object value=paramMap.get(variable);
					if(value==null) {
						throw new NullPointerException("参数值"+variable+"不能为空");
					}
					if(value instanceof Iterable<?>) {
						Iterable<?> iter=(Iterable<?>)value;
						Iterator<?> iterator=iter.iterator();
						while(iterator.hasNext()) {
							Object currentValue=iterator.next();
							newBoundSql.getParameterMappings().add(createNewParameterMapping(ms,String.valueOf(i),currentValue.getClass()));
							newBoundSql.setAdditionalParameter(String.valueOf(i),currentValue);
							i++;
						}
					}
					else {
						newBoundSql.getParameterMappings().add(createNewParameterMapping(ms,String.valueOf(i),value.getClass()));
						newBoundSql.setAdditionalParameter(String.valueOf(i),value);
						i++;
					}
				}
	    		MappedStatement ms2=MappedStatementFactory.changeMappedStatementResultType(ms,new SonOfSqlSource(newBoundSql), resultType);
				args[0] = ms2;
	    	}
	    	else if(methodName.contains("com.github.rexsheng.mybatis.mapper.DynamicMapper.insertBatch")) {
	    		Object parameterObject = args[1];
	    		if (parameterObject instanceof Map) {
	    			((Map<String, Object>)parameterObject).put("config", builderConfig);
	    		}
	    	}
	    	else if(methodName.contains("com.github.rexsheng.mybatis.mapper.DynamicMapper.selectByPageBuilder")) {
	    		Object parameterObject = args[1];
			    //获取参数中设置的返回值类型
	    		if(parameterObject instanceof com.github.rexsheng.mybatis.extension.QueryBuilder){
	            	com.github.rexsheng.mybatis.extension.QueryBuilder<?> queryBuilder=(com.github.rexsheng.mybatis.extension.QueryBuilder<?>)parameterObject;
	            	queryBuilder.setBuiderConfig(builderConfig);           	
	            		            	
//	            	BoundSql boundSql=ms2.getBoundSql(parameterObject);
	            	BoundSql boundSql = ms.getSqlSource().getBoundSql(parameterObject);
	            	 
	            	String countSql="select count(*) from ("+boundSql.getSql()+") a";

	            	Connection conn =ms.getConfiguration().getEnvironment().getDataSource().getConnection();

	            	PreparedStatement countStatement = null;
	            	ResultSet rs=null;
//	            	org.apache.ibatis.session.Configuration configuration = ms.getConfiguration();
//	            	BoundSql boundSql=ms.getBoundSql(parameterObject);
	                long totalItemCount = 0;

	                try {
	                	//预编译统计总记录数的sql
	                	countStatement = conn.prepareStatement(countSql);
	                	
	                    Object params = boundSql.getParameterObject();

	                    BoundSql countBs=copyAndNewBS(ms,boundSql,countSql);
	    				//当sql带有参数时，下面的这句话就是获取查询条件的参数 
	    				DefaultParameterHandler parameterHandler = new DefaultParameterHandler(ms,params,countBs);
	    				//经过set方法，就可以正确的执行sql语句  
	    				parameterHandler.setParameters(countStatement);  
	                    //执行查询语句
	                    rs = countStatement.executeQuery();
	                    while (rs.next()) {	                    	
	                    	totalItemCount = rs.getInt(1);
	                    	logger.debug("count result:{},sql:{}",totalItemCount,countSql);
	                    }
	                } catch (SQLException e) {
	                    e.printStackTrace();
	                } finally {
	                	try {
                			if(rs!=null) {
                				rs.close();
                			}
                			if(countStatement!=null) {
                				countStatement.close();
                			}
                			if(conn!=null) {
                				conn.close();
                			}
                        } catch (SQLException e) {
                            e.printStackTrace();
                        }
	                }
					if(totalItemCount==0) {
						//总条数为0时，无需继续查询列表
						return new PagedList<>(new ArrayList<>(),queryBuilder.getTable().getPageIndex(),queryBuilder.getTable().getPageSize(),totalItemCount);
					}
					String additionalSql="";
					List<ParameterMapping> newParamterMappings=boundSql.getParameterMappings()==null?new ArrayList<>():new ArrayList<>(boundSql.getParameterMappings());
					switch(builderConfig.getDbType().toLowerCase()) {
				    	case "mysql":
							if(queryBuilder.getTable().getPageSize()!=null) {
								additionalSql+=" LIMIT ?";
								newParamterMappings.add(createNewParameterMapping(ms,"table.pageSize",java.lang.Integer.class));
							}
							else {
								throw new NullPointerException("pageSize不能为空");
							}
							if(queryBuilder.getTable().getSkipSize()!=null) {
								additionalSql+=" OFFSET ?";
								newParamterMappings.add(createNewParameterMapping(ms,"table.skipSize",java.lang.Integer.class));
							}
							else {
								throw new NullPointerException("pageIndex/skipSize不能为空");
							}
							break;
						default:
							break;
					}
					
					BoundSql newBoundSql=new BoundSql(ms.getConfiguration(),boundSql.getSql()+additionalSql
							,newParamterMappings,boundSql.getParameterObject());
//					for(ParameterMapping mapping:newParamterMappings) {
//						String prop=mapping.getProperty();
//						if(boundSql.hasAdditionalParameter(prop)) {
//							newBoundSql.setAdditionalParameter(prop, boundSql.getAdditionalParameter(prop));
//						}
//					}
					
//	            	// 通过反射修改sql语句
//					Field field = boundSql.getClass().getDeclaredField("sql");
//					field.setAccessible(true);
//					field.set(boundSql, boundSql.getSql()+additionalSql);
					
					MappedStatement ms2=MappedStatementFactory.changeMappedStatementResultType(ms,new SonOfSqlSource(newBoundSql), queryBuilder.getOutputClazz());
					args[0] = ms2;
					
		            
					List<?> result=(List<?>) invocation.proceed();
					args[0] = ms;
	                PagedList<?> pagedList=new PagedList<>(result,queryBuilder.getTable().getPageIndex(),queryBuilder.getTable().getPageSize(),totalItemCount);
				    return pagedList;
	            }
			    
	    	}
	    	
	    }	    
	    return invocation.proceed();
	}

	
	public void setConfig(BuilderConfiguration builderConfig) {
		this.builderConfig=builderConfig;
		logger.debug("QueryBuilderConfiguration:{}",builderConfig);
	}
	 
    /**
     * 获取设置的返回值类型
     *
     * @param parameterObject
     * @return
     */
    private Class<?> getResultType(Object parameterObject,String resultTypeKey){
        if (parameterObject == null) {
            return null;
        } 
        else if(parameterObject instanceof com.github.rexsheng.mybatis.extension.QueryBuilder){
        	com.github.rexsheng.mybatis.extension.QueryBuilder<?> queryBuilder=(com.github.rexsheng.mybatis.extension.QueryBuilder<?>)parameterObject;
        	queryBuilder.setBuiderConfig(builderConfig);
        	return queryBuilder.getOutputClazz();
        } else if (parameterObject instanceof Class) {
            return (Class<?>)parameterObject;
        } else if (parameterObject instanceof Map) {
            //解决不可变Map的情况
            if(((Map<?,?>)(parameterObject)).containsKey(resultTypeKey)){
                Object result = ((Map<?,?>)(parameterObject)).get(resultTypeKey);
                return objectToClass(result,resultTypeKey);
            } else {
                return null;
            }
        } else {
            MetaObject metaObject = SystemMetaObject.forObject(parameterObject);
            Object result = metaObject.getValue(resultTypeKey);
            return objectToClass(result,resultTypeKey);
        }
    }
    
    private Object getResultValue(Object parameterObject,String resultKey){
        if (parameterObject == null) {
            return null;
        } 
        else if (parameterObject instanceof Map) {
            //解决不可变Map的情况
            if(((Map<?,?>)(parameterObject)).containsKey(resultKey)){
                Object result = ((Map<?,?>)(parameterObject)).get(resultKey);
                return result;
            } else {
                return null;
            }
        } else {
            MetaObject metaObject = SystemMetaObject.forObject(parameterObject);
            Object result = metaObject.getValue(resultKey);
            return result;
        }
    }

    /**
     * 将结果转换为Class
     *
     * @param object
     * @return
     */
    private Class<?> objectToClass(Object object,String resultTypeKey){
        if(object == null){
            return null;
        } else if(object instanceof Class){
            return (Class<?>)object;
        } else if(object instanceof String){
            try {
                return Class.forName((String)object);
            } catch (Exception e){
            	logger.error("非法的全限定类名字符串:" + object);
                throw new RuntimeException("非法的全限定类名字符串:" + object);
            }
        } else {
        	logger.error("方法参数类型错误，" + resultTypeKey + " 对应的参数类型只能为 Class 类型或者为 类的全限定名称字符串");
            throw new RuntimeException("方法参数类型错误，" + resultTypeKey + " 对应的参数类型只能为 Class 类型或者为 类的全限定名称字符串");
        }
    }
        
    /**
	 *构建一个新的BoundSql
	 */
	private BoundSql copyAndNewBS(MappedStatement mappedStatement, BoundSql boundSql, String countSql) {
		//根据新的sql构建一个全新的boundsql对象，并将原来的boundsql中的各属性复制过来
		BoundSql newBs=new BoundSql(mappedStatement.getConfiguration(),countSql
				,boundSql.getParameterMappings(),boundSql.getParameterObject());
		for(ParameterMapping mapping:boundSql.getParameterMappings()) {
			String prop=mapping.getProperty();
			if(boundSql.hasAdditionalParameter(prop)) {
				newBs.setAdditionalParameter(prop, boundSql.getAdditionalParameter(prop));
			}
		}
		return newBs;
	}
	
	private ParameterMapping createNewParameterMapping(MappedStatement mappedStatement,String name,Class<?> javaType) {
		ParameterMapping.Builder builder=new ParameterMapping.Builder(mappedStatement.getConfiguration(),name, javaType);
//		builder.jdbcType(JdbcType.INTEGER);
		return builder.build();
	}

	/**
	 *复制一个新的MappedStatement
	 */
    @SuppressWarnings("unused")
	private MappedStatement copyAndNewMS(MappedStatement ms,SqlSource ss) {
    	//通过builder对象重新构建一个MappedStatement对象
    	Builder builder =new Builder(ms.getConfiguration(),ms.getId(),ss,ms.getSqlCommandType());
    	builder.resource(ms.getResource());
    	builder.fetchSize(ms.getFetchSize());
    	builder.statementType(ms.getStatementType());
    	builder.keyGenerator(ms.getKeyGenerator());
    	builder.timeout(ms.getTimeout());
    	builder.parameterMap(ms.getParameterMap());
    	builder.resultMaps(ms.getResultMaps());
    	builder.resultSetType(ms.getResultSetType());
    	builder.cache(ms.getCache());
    	builder.flushCacheRequired(ms.isFlushCacheRequired());
    	builder.useCache(ms.isUseCache());
		return builder.build();
		
	}

    @Override
    public Object plugin(Object target) {
//    	if(target instanceof Executor){
//            //调用插件
//            return Plugin.wrap(target, this);
//        }
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        String beginDelimiter = properties.getProperty("beginDelimiter");
        if(beginDelimiter != null){
            builderConfig.setBeginDelimiter(beginDelimiter);
        }
        String endDelimiter = properties.getProperty("endDelimiter");
        if(beginDelimiter != null){
            builderConfig.setEndDelimiter(endDelimiter);
        }
        logger.debug("QueryBuilderConfiguration:{}",builderConfig);
    }
    
    /**
     * https://blog.csdn.net/fsy9595887/article/details/89000652
     * @author RexSheng
     * 2020年10月2日 上午12:13:50
     */
    class SonOfSqlSource implements SqlSource {
    	private BoundSql boundSql;
    	
    	public SonOfSqlSource(BoundSql boundSql) {
    		this.boundSql=boundSql;
    	}
		public BoundSql getBoundSql(Object arg0) {
			// TODO Auto-generated method stub
			return boundSql;
		}
    	
    }
}