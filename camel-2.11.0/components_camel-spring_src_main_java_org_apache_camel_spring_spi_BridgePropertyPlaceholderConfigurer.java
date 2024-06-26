/**
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.camel.spring.spi;

import java.util.Properties;

import org.apache.camel.CamelContext;
import org.apache.camel.component.properties.AugmentedPropertyNameAwarePropertiesParser;
import org.apache.camel.component.properties.PropertiesParser;
import org.apache.camel.component.properties.PropertiesResolver;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer;
import org.springframework.util.PropertyPlaceholderHelper;

/**
* A {@link PropertyPlaceholderConfigurer} that bridges Camel's <a href="http://camel.apache.org/using-propertyplaceholder.html">
* property placeholder</a> with the Spring property placeholder mechanism.
*/
public class BridgePropertyPlaceholderConfigurer extends PropertyPlaceholderConfigurer implements PropertiesResolver, AugmentedPropertyNameAwarePropertiesParser {

// NOTE: this class must be in the spi package as if its in the root package, then Spring fails to parse the XML
// files due some weird spring issue. But that is okay as having this class in the spi package is fine anyway.

private final Properties properties = new Properties();
private PropertiesResolver resolver;
private PropertiesParser parser;
private String id;
private PropertyPlaceholderHelper helper;

// to support both Spring 3.0 / 3.1+ we need to keep track of these as they have private modified in Spring 3.0
private String configuredPlaceholderPrefix;
private String configuredPlaceholderSuffix;
private String configuredValueSeparator;
private Boolean configuredIgnoreUnresolvablePlaceholders;

@Override
protected void processProperties(ConfigurableListableBeanFactory beanFactoryToProcess, Properties props) throws BeansException {
super.processProperties(beanFactoryToProcess, props);
// store all the spring properties so we can refer to them later
properties.putAll(props);
// create helper
helper = new PropertyPlaceholderHelper(
configuredPlaceholderPrefix != null ? configuredPlaceholderPrefix : DEFAULT_PLACEHOLDER_PREFIX,
configuredPlaceholderSuffix != null ? configuredPlaceholderSuffix : DEFAULT_PLACEHOLDER_SUFFIX,
configuredValueSeparator != null ? configuredValueSeparator : DEFAULT_VALUE_SEPARATOR,
configuredIgnoreUnresolvablePlaceholders != null ? configuredIgnoreUnresolvablePlaceholders : false);
}

@Override
public void setBeanName(String beanName) {
this.id = beanName;
super.setBeanName(beanName);
}

@Override
public void setPlaceholderPrefix(String placeholderPrefix) {
super.setPlaceholderPrefix(placeholderPrefix);
this.configuredPlaceholderPrefix = placeholderPrefix;
}

@Override
public void setPlaceholderSuffix(String placeholderSuffix) {
super.setPlaceholderSuffix(placeholderSuffix);
this.configuredPlaceholderSuffix = placeholderSuffix;
}

@Override
public void setValueSeparator(String valueSeparator) {
super.setValueSeparator(valueSeparator);
this.configuredValueSeparator = valueSeparator;
}

@Override
public void setIgnoreUnresolvablePlaceholders(boolean ignoreUnresolvablePlaceholders) {
super.setIgnoreUnresolvablePlaceholders(ignoreUnresolvablePlaceholders);
this.configuredIgnoreUnresolvablePlaceholders = ignoreUnresolvablePlaceholders;
}

@Override
public Properties resolveProperties(CamelContext context, boolean ignoreMissingLocation, String... uri) throws Exception {
// return the spring properties, if it
Properties answer = new Properties();
for (String u : uri) {
String ref = "ref:" + id;
if (ref.equals(u)) {
answer.putAll(properties);
} else if (resolver != null) {
Properties p = resolver.resolveProperties(context, ignoreMissingLocation, u);
if (p != null) {
answer.putAll(p);
}
}
}
// must not return null
return answer;
}

@Override
public String parseUri(String text, Properties properties, String prefixToken, String suffixToken,
String propertyPrefix, String propertySuffix, boolean fallbackToUnaugmentedProperty) throws IllegalArgumentException {

// first let Camel parse the text as it may contain Camel placeholders
String answer;
if (parser instanceof AugmentedPropertyNameAwarePropertiesParser) {
answer = ((AugmentedPropertyNameAwarePropertiesParser) parser).parseUri(text, properties, prefixToken, suffixToken,
propertyPrefix, propertySuffix, fallbackToUnaugmentedProperty);
} else {
answer = parser.parseUri(text, properties, prefixToken, suffixToken);
}

// then let Spring parse it to resolve any Spring placeholders
if (answer != null) {
answer = springResolvePlaceholders(answer, properties);
} else {
answer = springResolvePlaceholders(text, properties);
}
return answer;
}

@Override
public String parseUri(String text, Properties properties, String prefixToken, String suffixToken) throws IllegalArgumentException {
String answer = parser.parseUri(text, properties, prefixToken, suffixToken);
if (answer != null) {
answer = springResolvePlaceholders(answer, properties);
} else {
answer = springResolvePlaceholders(text, properties);
}
return answer;
}

@Override
public String parseProperty(String key, String value, Properties properties) {
String answer = parser.parseProperty(key, value, properties);
if (answer != null) {
answer = springResolvePlaceholders(answer, properties);
} else {
answer = springResolvePlaceholders(value, properties);
}
return answer;
}

/**
* Resolves the placeholders using Spring's property placeholder functionality.
*
* @param text   the text which may contain spring placeholders
* @param properties the properties
* @return the parsed text with replaced placeholders, or the original text as is
*/
protected String springResolvePlaceholders(String text, Properties properties) {
return helper.replacePlaceholders(text, properties);
}

public void setResolver(PropertiesResolver resolver) {
this.resolver = resolver;
}

public void setParser(PropertiesParser parser) {
this.parser = parser;
}

}