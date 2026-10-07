package com.google.common.collect;
public abstract class ImmutableList<E> extends java.util.AbstractList<E> { public static <E> Builder<E> builder(){return null;} public static final class Builder<E> { public Builder<E> add(E e){return this;} public Builder<E> addAll(Iterable<? extends E> i){return this;} public ImmutableList<E> build(){return null;} } }
