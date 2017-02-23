/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package software.betamax.tape.yaml;

import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;

import org.yaml.snakeyaml.introspector.BeanAccess;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.introspector.PropertyUtils;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Represent;
import org.yaml.snakeyaml.representer.Representer;

import java.beans.IntrospectionException;
import java.io.File;
import java.net.URI;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.Response;
import software.betamax.io.FileResolver;
import software.betamax.message.tape.RecordedRequest;
import software.betamax.message.tape.RecordedResponse;
import software.betamax.tape.RecordedInteraction;
import software.betamax.tape.Tape;

import static org.yaml.snakeyaml.DumperOptions.ScalarStyle.LITERAL;
import static org.yaml.snakeyaml.DumperOptions.ScalarStyle.PLAIN;

/**
 * Applies a fixed ordering to properties and excludes `null` valued
 * properties, empty collections and empty maps.
 */
public class TapeRepresenter extends Representer {
  TapeRepresenter(FileResolver fileResolver) {
    setPropertyUtils(new TapePropertyUtils());
    representers.put(URI.class, new RepresentURI());
    representers.put(File.class, new RepresentFile(fileResolver));
    representers.put(RecordedRequest.class, new RepresentRecordedRequest());
    representers.put(RecordedResponse.class, new RepresentRecordedResponse());
    representers.put(Headers.class, new RepresentHeaders());
  }

  @Override
  protected NodeTuple representJavaBeanProperty(Object bean, Property property, Object value, Tag
      customTag) {
    NodeTuple tuple = super.representJavaBeanProperty(bean, property, value, customTag);

    if (isNullValue(tuple) || isEmptySequence(tuple) || isEmptyMapping(tuple)) {
      return null;
    }

    if ("body".equals(property.getName())) {
      ScalarNode n = (ScalarNode) tuple.getValueNode();
      if (n.getStyle() == PLAIN.getChar()) {
        return tuple;
      } else {
        return new NodeTuple(tuple.getKeyNode(), new ScalarNode(n.getTag(), n.getValue(), n
            .getStartMark(), n.getEndMark(), LITERAL.getChar()));
      }
    }

    return tuple;
  }

  @Override protected Node representMapping(Tag tag, Map<?, ?> mapping, Boolean flowStyle) {
    return super.representMapping(tag, sort(mapping), flowStyle);
  }

  private <K, V> Map<K, V> sort(Map<K, V> self) {
    return new TreeMap<>(self);
  }

  private boolean isNullValue(NodeTuple tuple) {
    return tuple.getValueNode().getTag().equals(Tag.NULL);
  }

  private boolean isEmptySequence(NodeTuple tuple) {
    return tuple.getValueNode() instanceof SequenceNode && ((SequenceNode) tuple.getValueNode())
        .getValue().isEmpty();
  }

  private boolean isEmptyMapping(NodeTuple tuple) {
    return tuple.getValueNode() instanceof MappingNode && ((MappingNode) tuple.getValueNode())
        .getValue().isEmpty();
  }

  private class RepresentURI implements Represent {
    public Node representData(Object data) {
      return representScalar(Tag.STR, data.toString());
    }
  }

  private class RepresentFile implements Represent {
    private final FileResolver fileResolver;

    RepresentFile(FileResolver fileResolver) {
      this.fileResolver = fileResolver;
    }

    @Override public Node representData(Object data) {
      return representScalar(YamlTape.FILE_TAG, fileResolver.toPath((File) data));
    }
  }

  private class RepresentRecordedRequest implements Represent {
    @Override public Node representData(Object data) {
      RecordedRequest recordedRequest = (RecordedRequest) data;
      Tag tag = getTag(RecordedRequest.class, new Tag(RecordedRequest.class));
      return representSequence(tag, Arrays.asList(recordedRequest.method(), recordedRequest.url()
          .toString(), recordedRequest.headers(), recordedRequest.getBody()), true);
    }
  }

  private class RepresentRecordedResponse implements Represent {
    @Override public Node representData(Object data) {
      RecordedResponse recordedResponse = (RecordedResponse) data;
      Tag tag = getTag(RecordedResponse.class, new Tag(RecordedResponse.class));
      return representSequence(tag, Arrays.asList(recordedResponse.code(), recordedResponse
          .headers(), recordedResponse.getBody()), true);
    }
  }

  private class RepresentHeaders implements Represent {
    @Override public Node representData(Object data) {
      Map<String, List<String>> multimap = ((Headers) data).toMultimap();
      Map<String, String> map = new HashMap<>(multimap.keySet().size());
      // TODO: For simplicity, just use the first entry for each header in the multimap.
      for (Map.Entry<String, List<String>> entry : multimap.entrySet()) {
        map.put(entry.getKey(), entry.getValue().get(0));
      }
      return representMapping(Tag.MAP, map, true);
    }
  }

  public class TapePropertyUtils extends PropertyUtils {
    @Override protected Set<Property> createPropertySet(Class<?> type, BeanAccess bAccess) {
      try {
        Set<Property> properties = super.createPropertySet(type, bAccess);
        if (Tape.class.isAssignableFrom(type)) {
          return sort(properties, "name", "interactions");
        } else if (RecordedInteraction.class.isAssignableFrom(type)) {
          return sort(properties, "recorded", "request", "response");
        } else if (Request.class.isAssignableFrom(type)) {
          return sort(properties, "method", "uri", "headers", "body");
        } else if (Response.class.isAssignableFrom(type)) {
          return sort(properties, "status", "headers", "body");
        } else {
          return properties;
        }
      } catch (IntrospectionException e) {
        throw new RuntimeException(e);
      }
    }

    private Set<Property> sort(Set<Property> properties, String... names) {
      return Sets.newLinkedHashSet(Ordering.from(OrderedPropertyComparator.forNames(names))
          .sortedCopy(properties));
    }
  }

  public static class OrderedPropertyComparator implements Comparator<Property> {
    static OrderedPropertyComparator forNames(String... propertyNames) {
      return new OrderedPropertyComparator(propertyNames);
    }

    private OrderedPropertyComparator(String... propertyNames) {
      this.propertyNames = Arrays.asList(propertyNames);
    }

    public int compare(Property a, Property b) {
      return Ints.compare(propertyNames.indexOf(a.getName()), propertyNames.indexOf(b.getName()));
    }

    private List<String> propertyNames;
  }
}

