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

package org.waveprotocol.wave.model.supplement;

import org.waveprotocol.wave.model.id.WaveletId;
import org.waveprotocol.wave.model.version.HashedVersion;

/**
 * Defines the aspects of a wave view in which {@link SupplementedWaveImpl}
 * is interested.
 */
// TODO(anorth, hearnden): Replace wavelet ids with string conversation ids.
public interface SupplementWaveView {
  
  /**
   * @return the ids of all conversational wavelets.
   */
  Iterable<WaveletId> getWavelets();

  /**
   * @return the current version of a wavelet.
   * 
   * @param waveletId wavelet id
   */
  long getVersion(WaveletId waveletId);

  /**
   * @return the signature hash of the specified wavelet
   * at its current version.
   * 
   * @param waveletId wavelet id
   */
  HashedVersion getSignature(WaveletId waveletId);

  /**
   * @return true if the account viewing this wave is listed as an explicit participant.
   */
  boolean isExplicitParticipant();
}
