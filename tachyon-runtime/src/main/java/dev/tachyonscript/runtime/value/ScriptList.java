package dev.tachyonscript.runtime.value;

import dev.tachyonscript.api.value.Values;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The list created by list literals and list operations of scripts.
 *
 * <p>Scripts may share a list between threads (a top-level variable read by event handlers of
 * several Folia regions, or by asynchronous chat). Changes are therefore synchronized, so
 * concurrent changes never corrupt the list; reads are not, so reading stays as fast as an
 * {@link ArrayList} and at worst sees a slightly older state. Operations that walk the whole
 * list (filter, sort, joins...) work on a {@link #snapshot()} taken under the lock.
 */
public final class ScriptList extends ArrayList<Object> {

    private static final long serialVersionUID = 1L;

    public ScriptList() {
    }

    public ScriptList(int capacity) {
        super(capacity);
    }

    public ScriptList(Collection<?> elements) {
        super(elements);
    }

    /** A copy of the elements taken atomically. */
    public synchronized Object[] snapshot() {
        return toArray();
    }

    @Override
    public synchronized boolean add(Object element) {
        return super.add(element);
    }

    @Override
    public synchronized void add(int index, Object element) {
        super.add(index, element);
    }

    @Override
    public synchronized boolean addAll(Collection<?> elements) {
        return super.addAll(elements);
    }

    @Override
    public synchronized boolean addAll(int index, Collection<?> elements) {
        return super.addAll(index, elements);
    }

    @Override
    public synchronized Object set(int index, Object element) {
        return super.set(index, element);
    }

    @Override
    public synchronized Object remove(int index) {
        return super.remove(index);
    }

    @Override
    public synchronized boolean remove(Object element) {
        return super.remove(element);
    }

    @Override
    public synchronized boolean removeAll(Collection<?> elements) {
        return super.removeAll(elements);
    }

    @Override
    public synchronized boolean retainAll(Collection<?> elements) {
        return super.retainAll(elements);
    }

    @Override
    public synchronized boolean removeIf(Predicate<? super Object> filter) {
        return super.removeIf(filter);
    }

    @Override
    public synchronized void replaceAll(UnaryOperator<Object> operator) {
        super.replaceAll(operator);
    }

    @Override
    public synchronized void sort(Comparator<? super Object> comparator) {
        super.sort(comparator);
    }

    @Override
    public synchronized void clear() {
        super.clear();
    }

    @Override
    public synchronized Object[] toArray() {
        return super.toArray();
    }

    @Override
    public synchronized int indexOf(Object element) {
        return super.indexOf(element);
    }

    @Override
    public synchronized int lastIndexOf(Object element) {
        return super.lastIndexOf(element);
    }

    @Override
    public synchronized boolean contains(Object element) {
        return super.contains(element);
    }

    @Override
    public synchronized boolean equals(Object other) {
        return super.equals(other);
    }

    @Override
    public synchronized int hashCode() {
        return super.hashCode();
    }

    /** Text form used by templates: {@code [a, b, c]} with the canonical text of each element. */
    @Override
    public String toString() {
        Object[] elements = snapshot();
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < elements.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(elements[i] == this ? "(this list)" : Values.toString(elements[i]));
        }
        return out.append(']').toString();
    }
}
