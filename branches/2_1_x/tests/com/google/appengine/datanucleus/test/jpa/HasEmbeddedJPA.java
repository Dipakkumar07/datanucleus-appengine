/*
 * /**********************************************************************
 * Copyright (c) 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * **********************************************************************/

package com.google.appengine.datanucleus.test.jpa;

import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.Embedded;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

import org.datanucleus.api.jpa.annotations.Extension;

/**
 * @author Max Ross <maxr@google.com>
 */
@Entity
public class HasEmbeddedJPA {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Embedded
  @Extension(key="null-indicator-column", value="embeddedString")
  private EmbeddableJPA embeddable;

  @Embedded
  @Extension(key="null-indicator-column", value="EMBEDDEDSTRING")
  @AttributeOverrides({@AttributeOverride(name="embeddedString", column=@Column(name="EMBEDDEDSTRING")),
                       @AttributeOverride(name="multiVal", column=@Column(name="MULTIVAL"))})
  private EmbeddableJPA embeddable2;

  public Long getId() {
    return id;
  }

  public EmbeddableJPA getEmbeddable() {
    return embeddable;
  }

  public void setEmbeddable(EmbeddableJPA embeddable) {
    this.embeddable = embeddable;
  }

  public EmbeddableJPA getEmbeddable2() {
    return embeddable2;
  }

  public void setEmbeddable2(EmbeddableJPA embeddable2) {
    this.embeddable2 = embeddable2;
  }
}