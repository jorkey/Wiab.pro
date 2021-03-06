/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.waveprotocol.wave.model.document.util;

import org.waveprotocol.wave.model.document.ReadableDomDocument;

/**
 * Extracts the text content of subtrees in a readable document.
 *
 *
 * @param <N>
 * @param <E>
 * @param <T>
 */
public class TextExtractor<N, E extends N, T extends N> {
  private final ReadableDomDocument<N, E, T> document;

  public static <N, E extends N, T extends N> String
      extractInnerText(ReadableDomDocument<N, E, T> doc, E element) {
    return new TextExtractor<N, E, T>(doc).getInnerText(element);
  }

  public TextExtractor(ReadableDomDocument<N, E, T> document) {
    this.document = document;
  }

  public String getInnerText(E element) {
    StringBuilder builder = new StringBuilder();
    getInnerTextOfElement(element, builder);
    return builder.toString();
  }

  private void getInnerTextOfNode(N node, StringBuilder builder) {
    T maybeText = document.asText(node);
    if (maybeText != null) {
      getInnerTextOfText(maybeText, builder);
    } else {
      getInnerTextOfElement(document.asElement(node), builder);
    }
  }

  private void getInnerTextOfText(T text, StringBuilder builder) {
    builder.append(document.getData(text));
  }

  private void getInnerTextOfElement(E element, StringBuilder builder) {
    N child = document.getFirstChild(element);
    while (child != null) {
      getInnerTextOfNode(child, builder);
      child = document.getNextSibling(child);
    }
  }
}
