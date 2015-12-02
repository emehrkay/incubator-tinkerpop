/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.tinkerpop.gremlin.process.traversal.traverser;

import org.apache.tinkerpop.gremlin.process.traversal.Path;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalSideEffects;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.EmptyPath;
import org.apache.tinkerpop.gremlin.structure.util.Attachable;
import org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public final class DefaultTraverser<T> implements Traverser.Admin<T> {

    private T t;
    private long bulk = 1l;
    private String stepId;
    private Path path;
    private short loops = 0;
    private Set<String> tags = null;
    private transient TraversalSideEffects sideEffects;
    private boolean onlyLabeledPaths;
    private boolean oneBulk;
    private Object sack = null;

    /**
     * A no-args constructor  is necessary for Kryo serialization.
     */
    private DefaultTraverser() {

    }

    public DefaultTraverser(final T t, final Step<T, ?> step, final long initialBulk, final Path path, boolean onlyLabeledPaths, boolean oneBulk) {
        this.t = t;
        this.stepId = step.getId();
        this.bulk = initialBulk;
        this.path = path;
        this.sideEffects = step.getTraversal().getSideEffects();
        this.onlyLabeledPaths = onlyLabeledPaths;
        this.oneBulk = oneBulk;
        if (null != this.sideEffects.getSackInitialValue())
            this.sack = this.sideEffects.getSackInitialValue().get();
    }


    @Override
    public void addLabels(final Set<String> labels) {
        if (this.onlyLabeledPaths) {
            if (!labels.isEmpty())
                this.path = this.path.size() == 0 || !this.path.get(this.path.size() - 1).equals(this.t) ?
                        this.path.extend(this.t, labels) :
                        this.path.extend(labels);
        } else
            this.path = this.path.extend(labels);
    }

    @Override
    public void set(final T t) {
        this.t = t;
    }

    @Override
    public void incrLoops(final String stepLabel) {
        if (!this.oneBulk)
            this.loops++;
    }

    @Override
    public void resetLoops() {
        this.loops = 0;
    }

    @Override
    public String getStepId() {
        return this.stepId;
    }

    @Override
    public void setStepId(final String stepId) {
        this.stepId = stepId;
    }

    @Override
    public void setBulk(final long count) {
        this.bulk = count;
    }

    @Override
    public Admin<T> detach() {
        this.t = ReferenceFactory.detach(this.t);
        if (!(this.path instanceof EmptyPath))
            this.path = ReferenceFactory.detach(this.path);
        return this;
    }

    @Override
    public T attach(final Function<Attachable<T>, T> method) {
        // you do not want to attach a path because it will reference graph objects not at the current vertex
        if (this.t instanceof Attachable && !(((Attachable) this.t).get() instanceof Path))
            this.t = ((Attachable<T>) this.t).attach(method);
        return this.t;
    }

    @Override
    public void setSideEffects(final TraversalSideEffects sideEffects) {
        this.sideEffects = sideEffects;
    }

    @Override
    public TraversalSideEffects getSideEffects() {
        return this.sideEffects;
    }

    public Set<String> getTags() {
        if (null == this.tags) this.tags = new HashSet<>();
        return this.tags;
    }

    @Override
    public <R> Admin<R> split(final R r, final Step<T, R> step) {
        final DefaultTraverser<R> clone = (DefaultTraverser<R>) this.clone();
        clone.t = r;
        if (this.onlyLabeledPaths) {
            if (!step.getLabels().isEmpty())
                clone.path = this.path.clone().extend(r, step.getLabels());
        } else
            clone.path = this.path.clone().extend(r, step.getLabels());
        if (null != this.tags)
            clone.tags = new HashSet<>(this.tags);
        clone.sack = null == clone.sack ? null : null == clone.sideEffects.getSackSplitter() ? clone.sack : clone.sideEffects.getSackSplitter().apply(clone.sack);
        return clone;
    }

    @Override
    public Admin<T> split() {
        final DefaultTraverser<T> clone = (DefaultTraverser<T>) this.clone();
        clone.path = this.path.clone();
        if (null != this.tags)
            clone.tags = new HashSet<>(this.tags);
        clone.sack = null == clone.sack ? null : null == clone.sideEffects.getSackSplitter() ? clone.sack : clone.sideEffects.getSackSplitter().apply(clone.sack);
        return clone;
    }

    @Override
    public void merge(final Traverser.Admin<?> other) {
        if (!this.oneBulk)
            this.bulk = this.bulk + other.bulk();
        if (!other.getTags().isEmpty()) {
            if (this.tags == null) this.tags = new HashSet<>();
            this.tags.addAll(other.getTags());
        }
        if (null != this.sack && null != this.sideEffects.getSackMerger())
            this.sack = this.sideEffects.getSackMerger().apply(this.sack, other.sack());
    }

    @Override
    public T get() {
        return this.t;
    }

    @Override
    public <S> S sack() {
        return (S) this.sack;
    }

    @Override
    public <S> void sack(final S object) {
        this.sack = object;
    }

    @Override
    public Path path() {
        return this.path;
    }

    @Override
    public int loops() {
        return this.loops;
    }

    @Override
    public long bulk() {
        return this.bulk;
    }

    @Override
    public Traverser<T> clone() {
        try {
            final DefaultTraverser<T> clone = (DefaultTraverser<T>) super.clone();
            if (this.tags != null)
                clone.getTags().addAll(this.tags);
            clone.path = this.path.clone();
            return clone;
        } catch (final CloneNotSupportedException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    ///////////

    @Override
    public int hashCode() {
        return this.t.hashCode() + this.stepId.hashCode() + this.loops + this.path.hashCode();
    }

    @Override
    public boolean equals(final Object object) {
        return object instanceof DefaultTraverser &&
                ((DefaultTraverser) object).get().equals(this.t) &&
                ((DefaultTraverser) object).stepId.equals(this.stepId) &&
                ((DefaultTraverser) object).loops() == this.loops &&
                (null == this.sack || (null != this.sideEffects && null != this.sideEffects.getSackMerger())) && // hmmm... serialization in OLAP destroys the transient sideEffects
                ((DefaultTraverser) object).path().equals(this.path);
    }

    @Override
    public String toString() {
        return this.t.toString();
    }
}