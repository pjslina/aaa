�������ṩ�Ļ����ܴ��룬��������������ʵ�֣�����CacheDataLoader��ʵ�����ҵ��Service�ĵ��÷�ʽ��

1. CacheDataLoader ʵ����
package com.example.service.impl;

import com.example.service.CacheDataLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * �û���Ϣ���ݼ�����ʵ��
 */
@Slf4j
@Component
public class UserDataLoader implements CacheDataLoader<UserQueryParams, UserVO> {
    
    @Override
    public UserVO loadData(UserQueryParams params) {
        try {
            log.info("��ʼ�����ݿ�����û�����, ����: {}", params);
            
            // ����ʵ�ʵ���Mapper��Repository�����ݿ��������
            UserVO user = userMapper.selectUserById(params.getUserId());
            
            if (user == null) {
                log.warn("δ�ҵ��û�����, userId: {}", params.getUserId());
                return null;
            }
            
            log.info("�ɹ������û�����, userId: {}", params.getUserId());
            return user;
            
        } catch (Exception e) {
            log.error("�����û������쳣, params: {}", params, e);
            throw new RuntimeException("���ݼ���ʧ��", e);
        }
    }
}

/**
 * Ԫ���ݼ�����ʵ��
 */
@Slf4j
@Component
public class MetadataLoader implements CacheDataLoader<MetadataQuery, MetadataVO> {
    
    @Override
    public MetadataVO loadData(MetadataQuery params) {
        log.info("����Ԫ����, type: {}, code: {}", params.getType(), params.getCode());
        
        // ʵ�ʵ����ݼ����߼�
        return metadataMapper.selectMetadata(params);
    }
}

/**
 * ҵ�����ü�����
 */
@Slf4j
@Component 
public class ConfigDataLoader implements CacheDataLoader<ConfigQuery, ConfigVO> {
    
    @Override
    public ConfigVO loadData(ConfigQuery params) {
        log.info("����ҵ������, configType: {}, bizId: {}", 
                params.getConfigType(), params.getBizId());
        
        return configMapper.selectConfig(params);
    }
}

2. ����������
package com.example.vo;

import lombok.Data;
import java.io.Serializable;

/**
 * �û���ѯ����
 */
@Data
public class UserQueryParams implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Long userId;
    private String userCode;
    private Boolean includeDetail;
    
    public UserQueryParams(Long userId) {
        this.userId = userId;
    }
    
    public UserQueryParams(String userCode) {
        this.userCode = userCode;
    }
}

/**
 * Ԫ���ݲ�ѯ����
 */
@Data
public class MetadataQuery implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String type;
    private String code;
    private String system;
    
    public MetadataQuery(String type, String code) {
        this.type = type;
        this.code = code;
    }
}

/**
 * ���ò�ѯ����
 */
@Data
public class ConfigQuery implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String configType;
    private String bizId;
    private String env;
    
    public ConfigQuery(String configType, String bizId) {
        this.configType = configType;
        this.bizId = bizId;
    }
}

3. ��������ʵ��
package com.example.config;

import com.example.constant.CacheConstants;
import com.example.service.CacheConfig;
import org.springframework.stereotype.Component;

/**
 * �û���������
 */
@Component
public class UserCacheConfig implements CacheConfig {
    
    @Override
    public String getCacheName() {
        return CacheConstants.CACHE_NAME;
    }
    
    @Override
    public String getModulePrefix() {
        return CacheConstants.ModulePrefix.USER;
    }
    
    @Override
    public long getExpireSeconds() {
        return CacheConstants.ExpireTime.BUSINESS;
    }
    
    @Override
    public long getRefreshThreshold() {
        return CacheConstants.REFRESH_THRESHOLD;
    }
    
    @Override
    public String[] getKeyFields() {
        // ָ��ʹ��userId�ֶ���Ϊ����key��һ����
        return new String[]{"userId"};
    }
}

/**
 * Ԫ���ݻ�������
 */
@Component
public class MetadataCacheConfig implements CacheConfig {
    
    @Override
    public String getCacheName() {
        return CacheConstants.CACHE_NAME;
    }
    
    @Override
    public String getModulePrefix() {
        return CacheConstants.ModulePrefix.METADATA;
    }
    
    @Override
    public long getExpireSeconds() {
        return CacheConstants.ExpireTime.METADATA;
    }
    
    @Override
    public String[] getKeyFields() {
        return new String[]{"type", "code"};
    }
}

/**
 * ���û�������
 */
@Component
public class ConfigCacheConfig implements CacheConfig {
    
    @Override
    public String getCacheName() {
        return CacheConstants.CACHE_NAME;
    }
    
    @Override
    public String getModulePrefix() {
        return CacheConstants.ModulePrefix.CONFIG;
    }
    
    @Override
    public long getExpireSeconds() {
        return 12 * 60 * 60; // 12Сʱ
    }
}

4. ҵ��Service����ʵ��
package com.example.service.impl;

import com.example.cache.GenericCacheManager;
import com.example.config.UserCacheConfig;
import com.example.service.CacheDataLoader;
import com.example.vo.UserQueryParams;
import com.example.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * �û�����ʵ�� - չʾ��ε��û�����
 */
@Slf4j
@Service
public class UserService {
    
    @Autowired
    private GenericCacheManager cacheManager;
    
    @Autowired
    private UserCacheConfig userCacheConfig;
    
    @Autowired
    private UserDataLoader userDataLoader;
    
    // Ҳ����ֱ��ʹ��Lambda���ʽʵ��CacheDataLoader
    private final CacheDataLoader<UserQueryParams, UserVO> lambdaLoader = 
        params -> {
            // ֱ��������ʵ�����ݼ����߼�
            log.info("ͨ��Lambda�����û�����: {}", params.getUserId());
            return userMapper.selectUserById(params.getUserId());
        };
    
    /**
     * �����û�ID��ȡ�û���Ϣ��ʹ�û��棩
     */
    public UserVO getUserById(Long userId) {
        try {
            UserQueryParams params = new UserQueryParams(userId);
            
            // ���û����������ȡ����
            UserVO user = cacheManager.getCachedData(
                params,                    // ��ѯ����
                userCacheConfig,          // ��������
                userDataLoader,           // ���ݼ�����
                UserVO.class              // ��������
            );
            
            if (user == null) {
                log.warn("δ�ҵ��û���Ϣ, userId: {}", userId);
            }
            
            return user;
            
        } catch (Exception e) {
            log.error("��ȡ�û���Ϣ�쳣, userId: {}", userId, e);
            throw new RuntimeException("��ȡ�û���Ϣʧ��", e);
        }
    }
    
    /**
     * �����û������ȡ�û���Ϣ��ʹ��Lambda���ʽ��
     */
    public UserVO getUserByCode(String userCode) {
        UserQueryParams params = new UserQueryParams(userCode);
        
        return cacheManager.getCachedData(
            params,
            userCacheConfig,
            lambdaLoader,  // ʹ��Lambda���ʽ
            UserVO.class
        );
    }
    
    /**
     * ǿ��ˢ�»���
     */
    public void refreshUserCache(Long userId) {
        try {
            UserQueryParams params = new UserQueryParams(userId);
            
            cacheManager.refreshCache(
                params,
                userCacheConfig,
                userDataLoader
            );
            
            log.info("�û�����ˢ�³ɹ�, userId: {}", userId);
            
        } catch (Exception e) {
            log.error("ˢ���û������쳣, userId: {}", userId, e);
        }
    }
}

5. Ԫ���ݷ���ʾ��

package com.example.service.impl;

import com.example.cache.GenericCacheManager;
import com.example.config.MetadataCacheConfig;
import com.example.service.CacheDataLoader;
import com.example.vo.MetadataQuery;
import com.example.vo.MetadataVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Ԫ���ݷ���ʵ��
 */
@Slf4j
@Service
public class MetadataService {
    
    @Autowired
    private GenericCacheManager cacheManager;
    
    @Autowired
    private MetadataCacheConfig metadataConfig;
    
    /**
     * ʹ�������ڲ���ʵ��CacheDataLoader
     */
    private final CacheDataLoader<MetadataQuery, MetadataVO> metadataLoader = 
        new CacheDataLoader<MetadataQuery, MetadataVO>() {
            @Override
            public MetadataVO loadData(MetadataQuery params) {
                log.info("����Ԫ����: type={}, code={}", params.getType(), params.getCode());
                return metadataMapper.selectByTypeAndCode(params.getType(), params.getCode());
            }
        };
    
    /**
     * ��ȡԪ����
     */
    public MetadataVO getMetadata(String type, String code) {
        MetadataQuery params = new MetadataQuery(type, code);
        
        return cacheManager.getCachedData(
            params,
            metadataConfig,
            metadataLoader,
            MetadataVO.class
        );
    }
    
    /**
     * ������ȡԪ���ݣ�ÿ�����߻��棩
     */
    public List<MetadataVO> getMetadataList(List<MetadataQuery> queries) {
        return queries.stream()
            .map(query -> cacheManager.getCachedData(
                query, metadataConfig, metadataLoader, MetadataVO.class))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
}

6. ������VO��Mapperʾ��
package com.example.vo;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

/**
 * �û�ֵ����
 */
@Data
public class UserVO implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Long userId;
    private String userCode;
    private String userName;
    private String email;
    private String phone;
    private Integer status;
    private Date createTime;
    private Date updateTime;
}

/**
 * Ԫ����ֵ����
 */
@Data
public class MetadataVO implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Long id;
    private String type;
    private String code;
    private String name;
    private String value;
    private String description;
    private Integer sortOrder;
    private Integer status;
}

// Mapper�ӿ�ʾ��
package com.example.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {
    UserVO selectUserById(@Param("userId") Long userId);
    UserVO selectUserByCode(@Param("userCode") String userCode);
}

@Mapper
public interface MetadataMapper {
    MetadataVO selectMetadata(@Param("query") MetadataQuery query);
    MetadataVO selectByTypeAndCode(@Param("type") String type, @Param("code") String code);
}

ʹ�÷�ʽ�ܽ�
�����������ʵ��Serializable�ӿڵĲ�ѯ����

ʵ��CacheConfig�����û������ơ�����ʱ���

ʵ��CacheDataLoader���ṩ���ݼ����߼�

��Service�е��ã�ͨ��GenericCacheManager.getCachedData()����

���������ʹ�û����߼���ҵ���߼���ȫ���ҵ��Serviceֻ��Ҫ��ע���ݼ��صľ���ʵ�֣������ܻ��Զ����������С�����ˢ�¡��ֲ�ʽ�������⡣
---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

�������һ���ܺõ����⣡ȷʵ��Set�������Իᵼ�»���key��ͬ���Ӷ��޷����л��档

�����ṩ��CacheKeyGenerator�����У���Ȼ��Collection���ͽ����˴���������һ��Ǳ�����⡣���ҷ������޸�������⣺

�������
��CacheKeyGenerator.convertToString�����У�
private static String convertToString(Object value) {
    if (value == null) {
        return "";
    }
    
    if (value instanceof Collection) {
        Collection<?> collection = (Collection<?>) value;
        return collection.stream()
                .map(Object::toString)
                .sorted()  // �����Ԫ�ؽ���������
                .collect(Collectors.joining(","));
    }
    
    // ... ��������
}

��Ȼ�����жԼ���Ԫ�ؽ��������򣬵����Set�е�Ԫ�ز���Comparable���ͣ����������߼������⣬��Ȼ���ܵ���key��һ�¡�

�޸�����
����Ϊ���ṩһ������׳�Ľ��������

1. �޸�CacheKeyGenerator

package com.example.cache;

// ����import���ֲ���
import java.util.TreeSet;

/**
 * ��ǿ�Ļ���Key������
 */
@Slf4j
public class CacheKeyGenerator {
    
    // �������뱣�ֲ���...
    
    /**
     * ��ǿ��ת��������ȷ��Set���͵�һ����
     */
    private static String convertToString(Object value) {
        if (value == null) {
            return "";
        }
        
        if (value instanceof Set) {
            Set<?> set = (Set<?>) value;
            // ʹ��TreeSetȷ��˳��һ���ԣ�����ÿ��Ԫ�ؽ����ַ���ת��������
            return set.stream()
                    .map(Object::toString)
                    .sorted()  // ���ַ�����Ȼ˳������
                    .collect(Collectors.joining(","));
        }
        
        if (value instanceof Collection) {
            Collection<?> collection = (Collection<?>) value;
            return collection.stream()
                    .map(Object::toString)
                    .sorted()  // ���ַ�����Ȼ˳������
                    .collect(Collectors.joining(","));
        }
        
        if (value.getClass().isArray()) {
            Object[] array = (Object[]) value;
            return Arrays.stream(array)
                    .map(Object::toString)
                    .sorted()  // ���ַ�����Ȼ˳������
                    .collect(Collectors.joining(","));
        }
        
        return value.toString();
    }
    
    /**
     * ר�Ŵ���Set���͵�hashֵ����
     */
    public static String generateSetHash(Set<?> set) {
        if (set == null || set.isEmpty()) {
            return "";
        }
        
        String sortedString = set.stream()
                .map(Object::toString)
                .sorted()
                .collect(Collectors.joining(","));
        
        return Hashing.sha256()
                .hashString(sortedString, StandardCharsets.UTF_8)
                .toString()
                .substring(0, 16);
    }
}

2. MetricReqVO�Ĳ�������
package com.example.vo;

import lombok.Data;
import java.io.Serializable;
import java.util.Set;

/**
 * ָ���ѯ����VO
 */
@Data
public class MetricReqVO implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Set<String> periods;        // �ڼ伯��
    private Set<String> domainCodes;   // ������뼯��
    private Integer type;              // ����
    private String code;               // ����
    
    /**
     * ��дtoString������ȷ��Set��˳��һ����
     */
    @Override
    public String toString() {
        return "MetricReqVO{" +
                "periods=" + (periods != null ? 
                    periods.stream().sorted().collect(Collectors.toList()) : "null") +
                ", domainCodes=" + (domainCodes != null ? 
                    domainCodes.stream().sorted().collect(Collectors.toList()) : "null") +
                ", type=" + type +
                ", code='" + code + '\'' +
                '}';
    }
    
    /**
     * ��дequals��hashCode����������Set���ݵ�����ԣ�����˳��
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        MetricReqVO that = (MetricReqVO) o;
        
        if (periods != null ? !new TreeSet<>(periods).equals(new TreeSet<>(that.periods)) : that.periods != null) 
            return false;
        if (domainCodes != null ? !new TreeSet<>(domainCodes).equals(new TreeSet<>(that.domainCodes)) : that.domainCodes != null) 
            return false;
        if (type != null ? !type.equals(that.type) : that.type != null) return false;
        return code != null ? code.equals(that.code) : that.code == null;
    }
    
    @Override
    public int hashCode() {
        int result = periods != null ? new TreeSet<>(periods).hashCode() : 0;
        result = 31 * result + (domainCodes != null ? new TreeSet<>(domainCodes).hashCode() : 0);
        result = 31 * result + (type != null ? type.hashCode() : 0);
        result = 31 * result + (code != null ? code.hashCode() : 0);
        return result;
    }
}


3. ר�ŵ����ݼ�����ʵ��

package com.example.service.impl;

import com.example.service.CacheDataLoader;
import com.example.vo.MetricReqVO;
import com.example.vo.MetricResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.TreeSet;

/**
 * ָ�����ݼ����� - ר�Ŵ���Set����
 */
@Slf4j
@Component
public class MetricDataLoader implements CacheDataLoader<MetricReqVO, MetricResultVO> {
    
    @Override
    public MetricResultVO loadData(MetricReqVO params) {
        try {
            log.info("��ʼ����ָ������, ����: {}", params);
            
            // ��ʵ�ʲ�ѯǰ�����Զ�Set�������б�׼������
            MetricReqVO normalizedParams = normalizeParams(params);
            
            // ����Mapper��Service��ѯ����
            MetricResultVO result = metricMapper.selectMetrics(normalizedParams);
            
            log.info("ָ�����ݼ������, ����: {}, �������: {}", 
                    params, result != null ? result.getDataCount() : 0);
            
            return result;
            
        } catch (Exception e) {
            log.error("����ָ�������쳣, params: {}", params, e);
            throw new RuntimeException("ָ�����ݼ���ʧ��", e);
        }
    }
    
    /**
     * ������׼����ȷ��Set������˳��һ����
     */
    private MetricReqVO normalizeParams(MetricReqVO params) {
        MetricReqVO normalized = new MetricReqVO();
        
        // ��Set��������ȷ����ѯ������һ����
        if (params.getPeriods() != null) {
            normalized.setPeriods(new TreeSet<>(params.getPeriods()));
        }
        if (params.getDomainCodes() != null) {
            normalized.setDomainCodes(new TreeSet<>(params.getDomainCodes()));
        }
        
        normalized.setType(params.getType());
        normalized.setCode(params.getCode());
        
        return normalized;
    }
}

4. ��������
package com.example.config;

import com.example.constant.CacheConstants;
import com.example.service.CacheConfig;
import org.springframework.stereotype.Component;

/**
 * ָ�껺������
 */
@Component
public class MetricCacheConfig implements CacheConfig {
    
    @Override
    public String getCacheName() {
        return CacheConstants.CACHE_NAME;
    }
    
    @Override
    public String getModulePrefix() {
        return "METRIC"; // �Զ���ģ��ǰ׺
    }
    
    @Override
    public long getExpireSeconds() {
        return 30 * 60; // 30����
    }
    
    @Override
    public long getRefreshThreshold() {
        return 5 * 60; // 5����
    }
    
    @Override
    public String[] getKeyFields() {
        // ָ�����в��뻺��key���ɵ��ֶ�
        return new String[]{"periods", "domainCodes", "type", "code"};
    }
}

5. ҵ��Serviceʵ��

package com.example.service.impl;

import com.example.cache.CacheKeyGenerator;
import com.example.cache.GenericCacheManager;
import com.example.config.MetricCacheConfig;
import com.example.vo.MetricReqVO;
import com.example.vo.MetricResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * ָ�����ʵ��
 */
@Slf4j
@Service
public class MetricService {
    
    @Autowired
    private GenericCacheManager cacheManager;
    
    @Autowired
    private MetricCacheConfig metricCacheConfig;
    
    @Autowired
    private MetricDataLoader metricDataLoader;
    
    /**
     * ��ȡָ�����ݣ������棩
     */
    public MetricResultVO getMetrics(Set<String> periods, Set<String> domainCodes, 
                                    Integer type, String code) {
        MetricReqVO params = new MetricReqVO();
        params.setPeriods(periods);
        params.setDomainCodes(domainCodes);
        params.setType(type);
        params.setCode(code);
        
        return getMetrics(params);
    }
    
    /**
     * ��ȡָ�����ݣ���Ҫ������
     */
    public MetricResultVO getMetrics(MetricReqVO params) {
        try {
            // ���ɻ���key������־��¼
            String cacheKey = CacheKeyGenerator.generateKey(
                metricCacheConfig.getCacheName(),
                metricCacheConfig.getModulePrefix(),
                params,
                metricCacheConfig.getKeyFields()
            );
            
            log.debug("��ȡָ������, ����: {}, ����Key: {}", params, cacheKey);
            
            MetricResultVO result = cacheManager.getCachedData(
                params,
                metricCacheConfig,
                metricDataLoader,
                MetricResultVO.class
            );
            
            return result;
            
        } catch (Exception e) {
            log.error("��ȡָ�������쳣, params: {}", params, e);
            throw new RuntimeException("��ȡָ������ʧ��", e);
        }
    }
    
    /**
     * ���Է�������֤Set˳��Ӱ�컺��key
     */
    public void testCacheKeyConsistency() {
        Set<String> periods1 = Set.of("098343", "de2343", "fdfe31");
        Set<String> periods2 = Set.of("de2343", "fdfe31", "098343"); // ˳��ͬ
        
        MetricReqVO params1 = new MetricReqVO();
        params1.setPeriods(periods1);
        params1.setDomainCodes(Set.of("A", "B"));
        params1.setType(1);
        params1.setCode("test");
        
        MetricReqVO params2 = new MetricReqVO();
        params2.setPeriods(periods2);
        params2.setDomainCodes(Set.of("B", "A")); // ˳��Ҳ��ͬ
        params2.setType(1);
        params2.setCode("test");
        
        String key1 = CacheKeyGenerator.generateKeyWithoutTimestamp(
            metricCacheConfig.getCacheName(),
            metricCacheConfig.getModulePrefix(),
            params1,
            metricCacheConfig.getKeyFields()
        );
        
        String key2 = CacheKeyGenerator.generateKeyWithoutTimestamp(
            metricCacheConfig.getCacheName(),
            metricCacheConfig.getModulePrefix(),
            params2,
            metricCacheConfig.getKeyFields()
        );
        
        log.info("����1����Key: {}", key1);
        log.info("����2����Key: {}", key2);
        log.info("����Key�Ƿ�һ��: {}", key1.equals(key2));
    }
}

�ؼ��Ľ���
Set������ǿ����convertToString�����У���Set���ͽ���ר�Ŵ���ȷ��Ԫ������

������׼���������ݼ���ǰ�Բ������б�׼������

VO�����Ż�����дequals��hashCode����������Set���ݵ������

������֤���ṩ���Է�����֤����key��һ����

��֤���
���������Ľ��󣬼�ʹSet��Ԫ�ص�˳��ͬ��ֻҪ������ͬ�����ɵĻ���key�ͻ���ȫһ�£��Ӷ�ȷ�����������ʡ�

������ͨ������testCacheKeyConsistency()��������֤��һ�㡣
