/**
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */
package org.waveprotocol.box.server.rpc.render.view.impl;

import org.waveprotocol.box.server.rpc.render.view.IntrinsicReplyBoxView;
import org.waveprotocol.box.server.rpc.render.view.ReplyBoxView;
import org.waveprotocol.box.server.rpc.render.view.RootThreadView;

/**
 * Implements the reply box at the end of a root thread.
 *
 * @param <I> intrinsic reply box implementation
 */
public final class ReplyBoxViewImpl
    <I extends IntrinsicReplyBoxView> // \u2620
    extends AbstractStructuredView<ReplyBoxViewImpl.
    Helper<? super I>, I> // \u2620
    implements ReplyBoxView {

  /**
   * Handles structural queries on participants views.
   *
   * @param <I> intrinsic participants implementation
   */
  public interface Helper<I> {

    RootThreadView getParent(I impl);
  }

  public ReplyBoxViewImpl(Helper<? super I> helper, I impl) {
    super(helper, impl);
  }

  @Override
  public String getId() {
    return impl.getId();
  }

  @Override
  public RootThreadView getParent() {
    return helper.getParent(impl);
  }

  @Override
  public void setAvatarImageUrl(String imageUrl) {
    impl.setAvatarImageUrl(imageUrl);
  }
}