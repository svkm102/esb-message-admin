/**
 * Copyright (c) 2006 Red Hat, Inc.
 * All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * Red Hat, Inc. ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with Red Hat.
 */
package org.esbtools.message.admin.common.dao;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import javax.persistence.EntityManager;
import javax.persistence.Query;

import org.esbtools.message.admin.common.ConversionUtility;
import org.esbtools.message.admin.common.orm.MetadataEntity;
import org.esbtools.message.admin.model.MetadataField;
import org.esbtools.message.admin.model.MetadataResponse;
import org.esbtools.message.admin.model.MetadataType;

public class MetadataDAOImpl implements MetadataDAO {

    private final EntityManager mgr;
    private final static Logger log = Logger.getLogger(MetadataDAOImpl.class.getName());
    private static transient Map<MetadataType, MetadataResponse> treeCache = new HashMap<>();
    private static transient Map<String, List<String>> suggestionsCache = new HashMap<>();

    private Set<String> suggestedFields;

    public MetadataDAOImpl(EntityManager mgr, Properties config) {
        this.mgr=mgr;
        suggestedFields = new HashSet<String>(Arrays.asList(config.getProperty("headersWithSuggestedValues").split(",")));
    }

    private String getMetadataHash(MetadataType type) {
        String hash = "";
        if(type==MetadataType.SearchKeys || type==MetadataType.Entities) {
            Query query = mgr.createQuery("select f from MetadataEntity f where f.type = '" +type+"'");
            List<MetadataEntity> result = query.getResultList();
            if(result!=null && result.size()>0) {
                hash = (String) result.get(0).getValue();
            }
        }
        return hash;
    }

    private String markTreeDirty(MetadataType type) {
        String hash = null;
        if(type.isSearchKeyType()) {
            type = MetadataType.SearchKeys;
        } else {
            type = MetadataType.Entities;
        }
        Query query = mgr.createQuery("select f from MetadataEntity f where f.type = '" +type+"'");
        List<MetadataEntity> result = query.getResultList();
        if(result!=null && result.size()>0) {
            hash = UUID.randomUUID().toString();
            result.get(0).setValue(hash);
        }
        return hash;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.esbtools.message.admin.service.dao.MetadataDAO#getMetadataTree(org
     * .esbtools.message.admin.model.MetadataType)
     *
     * fetch all metadata fields based on the the type of the tree requested.
     * Then compute the tree from those fields and respond with the entire tree
     * on the tree field, and the result field as null.
     */
    @Override
    public MetadataResponse getMetadataTree(MetadataType type) {

        MetadataResponse result;
        if(type == MetadataType.Entities || type == MetadataType.SearchKeys) {
            String hash = getMetadataHash(type);
            if(treeCache.containsKey(type) && hash.contentEquals(treeCache.get(type).getHash())) {
                return treeCache.get(type);
            } else {
                result = new MetadataResponse();
                String inClause = null;
                if (type == MetadataType.Entities) {
                    inClause = "('Entities', 'Entity', 'System', 'SyncKey')";
                } else {
                    inClause = "('SearchKeys', 'SearchKey', 'XPATH', 'Suggestion')";
                }
                if (inClause != null) {
                    Query query = mgr.createQuery("select f from MetadataEntity f where f.type in " + inClause);
                    List<MetadataEntity> queryResult = (List<MetadataEntity>) query.getResultList();
                    result.setTree(makeTree(queryResult));
                    result.setHash(hash);
                    treeCache.put(type, result);
                    if(type == MetadataType.SearchKeys) {
                        updateSuggestions(result.getTree());
                    }
                }
            }
        } else {
            result = new MetadataResponse();
            result.setErrorMessage("Illegal Argument:" + type + ", Expected: Entities or SearchKeys");
        }
        return result;
    }

    /*
     * given a list of metadata entities with parent ids, create a tree of
     * Metadafields.
     */
    private static MetadataField makeTree(List<MetadataEntity> entities) {
        MetadataField root = null;
        Map<Long, MetadataField> map = new HashMap<Long, MetadataField>();
        int i = 0;
        for (MetadataEntity entity : entities) {
            i++;
            MetadataField field = ConversionUtility.convertToMetadataField(entity);
            if (entity.getType() == MetadataType.Entities || entity.getType() == MetadataType.SearchKeys) {
                root = field;
                root.setValue(entity.getType().toString());
            }
            map.put(field.getId(), field);
        }
        for (MetadataEntity entity : entities) {
            MetadataField field = map.get(entity.getId());
            MetadataField parent=null;
            if (entity.getParentId().intValue() != -1 && (parent=map.get(entity.getParentId()))!= null) {
                parent.addDescendant((field));
            }
        }
        return root;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.esbtools.message.admin.service.dao.MetadataDAO#addChildMetadataField
     * (java.lang.Long, java.lang.String,
     * org.esbtools.message.admin.service.model.MetadataType, java.lang.String)
     *
     * given a parent id, creates a metadata field and adds the new field as a
     * child of the given parent. returns the entire tree of the metadata type
     * and the parent field with all its children in the result field of the
     * response.
     */
    @Override
    public MetadataResponse addChildMetadataField(Long parentId, String name, MetadataType type, String value) {

        MetadataResponse result = new MetadataResponse();
        MetadataEntity curr = new MetadataEntity(type, name, value, parentId);
        if (parentId == -1L) {
            if (type != MetadataType.Entities && type != MetadataType.SearchKeys) {
                result.setErrorMessage("Illegal Argument:" + type + ", If parent = -1, Expected: Entities or SearchKeys");
            } else {
                markTreeDirty(type);
                mgr.persist(curr);
                result = getMetadataTree(type);
            }
        } else {
            MetadataField parent = getMetadataField(parentId);
            if(parent==null) {
                result.setErrorMessage("Illegal Argument:parent "+parentId+ " not found!");
            } else if (!curr.canBeChildOf(parent.getType())) {
                result.setErrorMessage("Illegal Argument: " + type + " can not be a child of " + parent.getType());
            } else {
                markTreeDirty(type);
                mgr.persist(curr);
                result = createMetadataResult(parent);
            }
        }
        return result;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.esbtools.message.admin.service.dao.MetadataDAO#updateMetadataField
     * (java.lang.Long, java.lang.String,
     * org.esbtools.message.admin.service.MetadataType, java.lang.String)
     *
     * given a field id, overwrite the name, type and value of the metadata
     * field return the entire metadata tree and the parent of the field being
     * updated in the result field of the response.
     */
    @Override
    public MetadataResponse updateMetadataField(Long id, String name, MetadataType type, String value) {

        MetadataResponse result = new MetadataResponse();
        MetadataEntity entity = mgr.find(MetadataEntity.class, id);

        if (entity == null) {
            result.setErrorMessage("Entity not found:" + id);
        } else {
            MetadataField parent = getMetadataField(entity.getParentId());
            if (parent == null) {
                result.setErrorMessage("Parent (" + entity.getParentId() + ") of Entity " + id + "not found!");
            } else if (!entity.canBeChildOf(parent.getType())) {
                result.setErrorMessage(type + " cannot be child of " + parent.getType());
            } else {
                entity.setName(name);
                entity.setType(type);
                entity.setValue(value);
                markTreeDirty(type);
                result = createMetadataResult(parent);
            }
        }
        return result;

    }

    // keep children for history/ recovery, delete only current field
    @Override
    public MetadataResponse deleteMetadataField(Long id) {
        MetadataResponse result = new MetadataResponse();
        MetadataEntity entity = mgr.find(MetadataEntity.class, id);
        mgr.remove(entity);
        if (entity.getParentId() != -1L) {
            MetadataField parent = getMetadataField(entity.getParentId());
            markTreeDirty(parent.getType());
            result = createMetadataResult(parent);
        }
        return result;
    }

    /*
     * given a Metadata field, create a MetadataResponse by looking up the
     * entire tree. set the input field as the result in the MetadataResponse.
     */
    private MetadataResponse createMetadataResult(MetadataField field) {
        MetadataResponse result = new MetadataResponse();
        if (field.getType().isSyncKeyType()) {
            result.setTree(getMetadataTree(MetadataType.Entities).getTree());
        } else {
            result.setTree(getMetadataTree(MetadataType.SearchKeys).getTree());
        }
        result.setResult(searchField(result.getTree(), field));
        return result;
    }

    private MetadataField getMetadataField(Long id) {

        // if it is a top level field, return null;
        MetadataEntity current = null;
        current = mgr.find(MetadataEntity.class, id);
        if (current != null) {
            return ConversionUtility.convertToMetadataField(current);
        }
        return null;
    }

    /*
     * DFS search
     */
    private MetadataField searchField(MetadataField tree, MetadataField field) {

        MetadataField result = null;
        if (tree != null && field != null) {
            if (tree.getId() == field.getId()) {
                return tree;
            } else {
                for (MetadataField child : tree.getChildren()) {
                    MetadataField dfsResult = searchField(child, field);
                    if (dfsResult != null) {
                        return dfsResult;
                    }
                }
            }
        }
        return result;
    }

    @Override
    public void sync(String entity, String system, String key, String... values) {
        // TODO Auto-generated method stub

    }

    @Override
    public Map<String, List<String>> getSearchKeyValueSuggestions() {

        // ensure cache exists and is upto date.
        getMetadataTree(MetadataType.SearchKeys).getTree();
        return suggestionsCache;
    }

    private void updateSuggestions(MetadataField searchKeysTree) {

        Map<String, List<String>> newSuggestions = new HashMap<String, List<String>>();
        if(searchKeysTree!=null && searchKeysTree.getChildren().size()>0) {
            for (MetadataField searchKey : searchKeysTree.getChildren()) {
                if (suggestedFields.contains(searchKey.getValue())) {
                    List<String> values = new ArrayList<String>();
                    for (MetadataField suggestion : searchKey.getSuggestions()) {
                        values.add(suggestion.getValue());
                    }
                    newSuggestions.put(searchKey.getValue(), values);
                }
            }
        }
        suggestionsCache = newSuggestions;
    }

    @Override
    public void ensureSuggestionsArePresent(Map<String, List<String>> extractedHeaders) {

        for(String suggestedField: suggestedFields) {

            List<String> extractedValues = extractedHeaders.get(suggestedField);
            if(extractedValues!=null && extractedValues.size()>0) {
                if(!suggestionsCache.containsKey(suggestedField)) {
                    Long parentId = treeCache.get(MetadataType.SearchKeys).getTree().getId();
                    addChildMetadataField(parentId, suggestedField, MetadataType.SearchKey, suggestedField);
                }
                for(String extractedValue: extractedValues) {
                    Long searchKeyId = null;
                    if(!suggestionsCache.get(suggestedField).contains(extractedValue)) {
                        if(searchKeyId==null) {
                            searchKeyId = fetchSearchKeyId(suggestedField);
                        }
                        // fetch method can return null
                        if(searchKeyId!=null) {
                            addChildMetadataField(searchKeyId, extractedValue, MetadataType.Suggestion, extractedValue);
                        } else {
                            log.severe("unable to find search key to add suggestion!");
                        }
                    }
                }
            }
        }
    }

    private Long fetchSearchKeyId(String suggestedField) {
        Query query = mgr.createQuery("select f from MetadataEntity f where f.value = :value");
        query.setParameter("value", suggestedField);
        List<MetadataEntity> queryResult = (List<MetadataEntity>) query.getResultList();
        if (queryResult != null && queryResult.size() != 0) {
            return queryResult.get(0).getId();
        }
        return null;
    }

}
