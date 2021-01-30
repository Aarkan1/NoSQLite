package database;

import com.aventrix.jnanoid.jnanoid.NanoIdUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import entities.User;
import utilities.Rewriter;
import utilities.Utils;

import java.lang.annotation.AnnotationTypeMismatchException;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.*;

public class Collection<T> {
  private Class<T> klass;
  private String klassName;
  private Connection conn;
  private List<WatchHandler> watchers = new ArrayList<>();
  private ObjectMapper mapper = new ObjectMapper();
  private String idField;

  Collection(Connection conn, Class<T> klass) {
    this.klass = klass;
    this.conn = conn;
    klassName = klass.getSimpleName();

    for(Field field : klass.getDeclaredFields()) {
      if(field.isAnnotationPresent(Id.class)) {
        idField = field.getName();
        break;
      }
    }

    if(idField == null) idField = "id";

    // ignore failure on field name mismatch
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    try {
//      conn.createStatement().execute("drop table if exists " + klassName);
      conn.createStatement().execute("create table if not exists " + klassName +
              "(key TEXT PRIMARY KEY UNIQUE NOT NULL, " +
              "value JSON NOT NULL)");
    } catch (SQLException e) {
      e.printStackTrace();
    }
  }

  public T save(Object document) {
    try {
      if(document.getClass() != klass) {
        throw new TypeMismatchException(String.format("'%s' cannot be saved in a '%s' collection", document.getClass().getSimpleName(), klassName));
      }
    } catch (TypeMismatchException e) {
      e.printStackTrace();
      return null;
    }

    Map<String, String> field = getIdField(document);
    try {
      String json = mapper.writeValueAsString(document);
      String q = String.format("insert into %s values(?, json(?)) " +
              "on conflict(key) do update set value=json(excluded.value)", klassName);
      PreparedStatement stmt = conn.prepareStatement(q);
      stmt.setString(1, field.get("id"));
      stmt.setString(2, json);
      stmt.executeUpdate();
      updateWatchers(new WatchData(klassName, "save", Collections.singletonList(document)));
    } catch (SQLException | JsonProcessingException e) {
      e.printStackTrace();
    }
    return (T) document;
  }

  public T save(Object[] documents) {
    try {
      if(documents[0].getClass() != klass) {
        throw new TypeMismatchException(String.format("'%s' cannot be saved in a '%s' collection", documents[0].getClass().getSimpleName(), klassName));
      }
    } catch (TypeMismatchException e) {
      e.printStackTrace();
      return null;
    }

    try {
      conn.setAutoCommit(false);
      String q = "insert into "+klassName+" values(?, json(?)) " +
              "on conflict(key) do update set value=json(excluded.value)";
      PreparedStatement stmt = conn.prepareStatement(q);

      for(Object model : documents) {
        Map<String, String> field = getIdField(model);
        String json = mapper.writeValueAsString(model);

        stmt.setString(1, field.get("id"));
        stmt.setString(2, json);
        stmt.executeUpdate();
      }

      conn.commit();
      stmt.close();

      updateWatchers(new WatchData(klassName, "save", Arrays.asList(documents)));
    } catch (SQLException | JsonProcessingException e) {
      e.printStackTrace();
    } finally {
      try {
        conn.setAutoCommit(true);
      } catch (SQLException e) {
        e.printStackTrace();
      }
    }
    return (T) documents;
  }

  public List<T> find() {
    return find(0, 0);
  }

  public List<T> find(int limit) {
    return find(limit, 0);
  }

  public List<T> find(int limit, int offset) {
    try {
      String jsonArray = findAsJson(null, limit, offset);
      return mapper.readValue(jsonArray, mapper.getTypeFactory().constructCollectionType(List.class, klass));
    } catch (JsonProcessingException e) {
      e.printStackTrace();
    }
    return new ArrayList<>();
  }

  public List<T> find(String filter) {
    return find(filter, 0, 0);
  }

  public List<T> find(String filter, int limit) {
    return find(filter, limit, 0);
  }

  public List<T> find(String filter, int limit, int offset) {
    try {
      String jsonArray = findAsJson(filter, limit, offset);
      return mapper.readValue(jsonArray, mapper.getTypeFactory().constructCollectionType(List.class, klass));
    } catch (JsonProcessingException e) {
      e.printStackTrace();
    }
    return new ArrayList<>();
  }

  public String findAsJson() {
    return findAsJson(null, 0, 0);
  }

  public String findAsJson(int limit) {
    return findAsJson(null, limit, 0);
  }

  public String findAsJson(int limit, int offset) {
    return findAsJson(null, limit, offset);
  }

  public String findAsJson(String filter) {
    return findAsJson(filter, 0, 0);
  }

  public String findAsJson(String filter, int limit) {
    return findAsJson(filter, limit, 0);
  }

  public String findAsJson(String filter, int limit, int offset) {
    try {
      if(filter == null) {
        ResultSet rs = conn.createStatement().executeQuery(
                String.format("select group_concat(value) from (select value from %1$s" + (limit == 0 ? ")" : " limit %2$d offset %3$d)"),
                        klassName, limit, offset));
        return "[" + rs.getString(1) + "]";
      }

      Map<String, List<String>> filters = generateWhereClause(filter);
      String q = String.format("select group_concat(value) from (select value from %1$s"
              + filters.get("query").get(0) + (limit == 0 ? ")" : " limit %2$d offset %3$d)"), klassName, limit, offset);
      PreparedStatement stmt = conn.prepareStatement(q);

      for(int i = 0; i < filters.get("paths").size() * 2; i+=2) {
        stmt.setString(i+1, "$." + filters.get("paths").get(i / 2));
        String value = filters.get("values").get(i / 2);

        if(Utils.isNumeric(value)) {
          if(value.contains(".")) { // with decimals
            try {
              stmt.setDouble(i+2, Double.parseDouble(value));
            } catch (Exception tryFloat) {
              try {
                stmt.setFloat(i+2, Float.parseFloat(value));
              } catch (Exception e) {
                e.printStackTrace();
              }
            }
          } else { // without decimals
            try {
              stmt.setInt(i+2, Integer.parseInt(value));
            } catch (Exception tryLong) {
              try {
                stmt.setLong(i+2, Long.parseLong(value));
              } catch (Exception e) {
                e.printStackTrace();
              }
            }
          }
        } else { // not a number
          stmt.setString(i+2, value);
        }
      }
      ResultSet rs = stmt.executeQuery();
      return "[" + rs.getString(1) + "]";
    } catch (SQLException e) {
      e.printStackTrace();
    }
    return null;
  }

  public T findById(String id) {
    try {
      String q = String.format("select value from %1$s where key=? limit 1", klassName);
      PreparedStatement stmt = conn.prepareStatement(q);
      stmt.setString(1, id);
      ResultSet rs = stmt.executeQuery();

      return mapper.readValue(rs.getString(1), klass);
    } catch (SQLException | JsonProcessingException e) {
      e.printStackTrace();
    }
    return null;
  }

  public boolean delete(Object obj) {

    updateWatchers(new WatchData(klassName, "delete", Collections.singletonList(obj)));
    return false;
  }

  // find sort
  // find filter sort
  // save raw json
  // deleteById
  // delete by filter
  // change field name
  // update single field - update User set value = json_set(value, '$.cat.color', 'blue')
  // updateById single field - update User set value = json_set(value, '$.cat.color', 'blue') where key = id
  // remove single field - update User set value = json_remove(value, '$.cat.color')

  public void watch(WatchHandler watcher) {
    watchers.add(watcher);
  }

  private void updateWatchers(WatchData watchData) {
    watchers.forEach(w -> w.handle(watchData));
  }

  private Map<String, List<String>> generateWhereClause(String filter) {
    filter = filter.replace(" ", "");
    List<String> paths = new ArrayList<>();
    List<String> values = new ArrayList<>();
    String query = " where" + new Rewriter("([\\w\\.\\[\\]]+)(=~|>=|<=|!=|<|>|=)([\\w\\.\\[\\]]+)(\\)\\|\\||\\)&&|&&|\\|\\|)?") {
      public String replacement() {
        paths.add(group(1));
        String comparator = group(2) + " ?";
        if (group(2).equals("=~")) {
          comparator = "like ?";
          String val = group(3);
          if(val.contains("%") || val.contains("_")) {
            values.add(val);
          } else {
            values.add("%" + val + "%");
          }
        } else {
          values.add(group(3));
        }
        String andOr = group(4) == null ? ""
                : (group(4).equals("&&") ? "and"
                : group(4).equals("||") ? "or"
                : group(4).equals(")&&") ? ")and" : ")or");
        return String.format(" json_extract(value, ?) %s %s", comparator, andOr);
      }
    }.rewrite(filter);

    Map<String, List<String>> map = new HashMap<>();
    map.put("query", Collections.singletonList(query));
    map.put("paths", paths);
    map.put("values", values);

    return map;
  }

  private Map<String, String> getIdField(Object model) {
    Map<String, String> idValues = new HashMap<>();

    if(idField != null) {
      try {
        Field field = model.getClass().getDeclaredField(idField);
        field.setAccessible(true);
        if (field.get(model) == null) {
          // generate random id
          field.set(model, NanoIdUtils.randomNanoId());
        }
        idValues.put("name", field.getName());
        idValues.put("id", (String) field.get(model));
      } catch (NoSuchFieldException | IllegalAccessException e) {
        e.printStackTrace();
      }
    } else { // @Id has a custom field name
      try {
        for(Field field : model.getClass().getDeclaredFields()) {
          if(field.isAnnotationPresent(Id.class)) {
            field.setAccessible(true);
            if(field.get(model) == null) {
              // generate random id
              field.set(model, NanoIdUtils.randomNanoId());
            }
            idValues.put("name", field.getName());
            idValues.put("id", (String) field.get(model));
            break;
          }
        }
      } catch (IllegalAccessException e) {
        e.printStackTrace();
      }
    }

    return idValues;
  }
}
