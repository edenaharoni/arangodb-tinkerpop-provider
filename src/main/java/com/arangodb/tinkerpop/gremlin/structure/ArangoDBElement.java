package com.arangodb.tinkerpop.gremlin.structure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import org.apache.commons.collections4.map.AbstractHashedMap;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.VertexProperty.Cardinality;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.arangodb.tinkerpop.gremlin.structure.ArangoDBVertex.ArangoDBVertexProperty;
import com.arangodb.tinkerpop.gremlin.utils.ArangoDBUtil;

/**
 * The ArangoDB base element class (used by edges and vertices). 
 * 
 * @author Achim Brandt (http://www.triagens.de)
 * @author Johannes Gocke (http://www.triagens.de)
 * @author Guido Schwab (http://www.triagens.de)
 */

public abstract class ArangoDBElement<U> extends AbstractHashedMap<String, U> implements Element {
	
	/**
	 * The Class ArangoDBProperty.
	 *
	 * @param <U> the value type
	 */
	
	public static class ArangoDBElementProperty<V> extends HashEntry<String, V> implements Property<V>, Entry<String, V> {
    	
		/** The property owner. */
		
		protected ArangoDBElement<V> owner;
		
		/** The cardinality */
		private Cardinality cardinality;

		/**
		 * Instantiates a new ArangoDB property.
		 *
		 * @param next the next
		 * @param hashCode the hash code
		 * @param key the key
		 * @param value the value
		 * @param owner the element
		 */
		
		protected ArangoDBElementProperty(
			final HashEntry<String, V> next,
			final int hashCode,
			final Object key,
			final V value,
			final ArangoDBElement<V> owner) {
			super(next, hashCode, key, value);
			this.owner = owner;
		}
		
		/**
		 * Util constructor to use ArangoDBProperty in the ArangoElementPropertyIterator when 
		 * flattening multi-Cardinality properties.
		 * @param key
		 * @param value
		 * @param element
		 * @param cardinality
		 */
		protected ArangoDBElementProperty(Object key, V value, ArangoDBVertex<V> element, Cardinality cardinality) {
			super(null, 0, key, value);
			this.owner = element;
			this.cardinality = cardinality;
		}

		@Override
		public Element element() {
			return owner;
		}

		@Override
		public boolean isPresent() {
			return super.getValue() != null;
		}

		@Override
		public String key() {
			return getKey();
		}

		@Override
		public void remove() {
			if (cardinality.equals(Cardinality.single)) {
				owner.remove(key);
			}
			else {		// The value might be inside a collection
				V oldValue = owner.remove(key);
				if (oldValue instanceof Collection) {
					((Collection<?>)oldValue).remove(value);
					owner.put((String) key, oldValue);
				}
			}
		}

		@Override
		public V value() throws NoSuchElementException {
			V value = super.getValue();
			if (value == null) {
				throw new NoSuchElementException();
			}
			return value;
		}
		
		public void cardinality(Cardinality cardinality) {
			this.cardinality = cardinality;
		}
		
		public Cardinality cardinality() {
			return this.cardinality;
		}
		
		
		@Override
		public V setValue(V value) {
			V oldValue = super.setValue(value);
			if (!value.equals(oldValue)) {
				owner.save();
			}
			return oldValue;
		}

		@Override
	    public String toString() {
	    	return StringFactory.propertyString(this);
	    }

		@Override
		public boolean equals(Object obj) {
			return ElementHelper.areEqual(this, obj);
		}
		
		
		
		
    }
	
	
	public static class ArangoElementPropertyIterator<P extends Property<V>, V> implements Iterator<P> {
		
		/** The parent map */
        private final ArangoDBElement<V> parent;
        /** The last returned entry */
        private P last;
        /** The next entry */
        private P next;
        /** Keys to skip */
		private List<String> filterKeys;
		
		private boolean inMultiple = false;
		private P multiNext;
		private Iterator<Object> multiIt;
    	
        @SuppressWarnings("unchecked")
		protected ArangoElementPropertyIterator(
        	final ArangoDBElement<V> parent,
        	List<String> filterKeys) {
    		this.parent = parent;
        	Set<String> keys = new HashSet<>(parent.keySet());
        	if (keys.isEmpty()) {
        		this.next = null; 
        	}
        	else {
            	if (filterKeys.isEmpty()) {
        			this.filterKeys = new ArrayList<>(keys);
        		}
            	else {
            		this.filterKeys = filterKeys;
            	}
            	next = (P)parent.getEntry(this.filterKeys.remove(0));
        	}
        }
        
        protected ArangoElementPropertyIterator(final ArangoDBElement<V> parent) {
        	this(parent, new ArrayList<>(0));            
        }
        
        public boolean hasNext() {
        	return next != null;
        }

		@SuppressWarnings("unchecked")
		@Override
		public P next() {
			P newCurrent = next;
            if (next == null)  {
                throw new NoSuchElementException(AbstractHashedMap.NO_NEXT_ENTRY);
            }
			if (!inMultiple) {
				if (filterKeys.isEmpty()) {
	    			next = null;
	    		}
				else {
					next = (P)parent.getEntry(filterKeys.remove(0));
				}
				assert newCurrent instanceof ArangoDBVertexProperty;
				ArangoDBVertexProperty<?> arangoprop = (ArangoDBVertexProperty<?>)newCurrent;
            	if (arangoprop.cardinality() != Cardinality.single) {
            		multiNext = next;
                	if (arangoprop.getValue() instanceof Collection) {
                		multiIt = ((Collection<Object>)arangoprop.getValue()).iterator();
                		if (multiIt.hasNext()) {
                			newCurrent = (P) new ArangoDBVertexProperty<Object>(arangoprop.getKey(), multiIt.next(), (ArangoDBVertex<Object>) arangoprop.element(), arangoprop.cardinality());
                			if (multiIt.hasNext()) {
            					next = (P) new ArangoDBVertexProperty<Object>(newCurrent.key(), multiIt.next(), (ArangoDBVertex<Object>) arangoprop.element(), arangoprop.cardinality());
            					inMultiple = true;
            				}
                		}
                		else {
                			throw new NoSuchElementException("Multivalued property has no values. " + AbstractHashedMap.NO_NEXT_ENTRY);
                		}
                	}
                }
			}
			else {
				if (multiIt.hasNext()) {
					next = (P) new ArangoDBVertexProperty<Object>(newCurrent.key(), multiIt.next(), (ArangoDBVertex<Object>) newCurrent.element(), ((ArangoDBElementProperty<?>) newCurrent).cardinality());
				}
				else {
					inMultiple = false;
					next = multiNext;
				}
			}
            last = newCurrent;
            return newCurrent;
		}
		
		protected P currentEntry() {
            return last;
        }
		
		
	}
	
	
	/** The Constant logger. */
	
	private static final Logger logger = LoggerFactory.getLogger(ArangoDBElement.class);
	
	/** ArangoDB internal id. */

	private String arango_db_id;
	
	/** ArangoDB internal key - mapped to Tinkerpop's ID. */

	private String arango_db_key;

	/** ArangoDB internal revision. */

	private String arango_db_rev;
	
	/** The collection in which the element is placed. */

	private String arango_db_collection;

	/** the graph of the document. */

	protected ArangoDBGraph graph;
	
	/**  Flag to indicate if the element is paired to a document in the DB. */
	
	protected boolean paired = false;
	
	
	/**
	 * Constructor used for ArabgoDB JavaBeans serialisation.
	 */
	public ArangoDBElement() {
		super(4, 0.75f);
	}
	
	/**
	 * Instantiates a new ArangoDB element.
	 *
	 * @param graph the graph
	 * @param collection the collection
	 */
	public ArangoDBElement(ArangoDBGraph graph, String collection) {
		super(4, 0.75f);
		this.graph = graph;
		this.arango_db_collection = collection;
		
	}
	
	/**
	 * Instantiates a new ArangoDB element.
	 *
	 * @param graph the graph
	 * @param collection the collection
	 * @param key the key
	 */
	public ArangoDBElement(ArangoDBGraph graph, String collection, String key) {
		super(4, 0.75f);
		this.graph = graph;
		this.arango_db_collection = collection;
		this.arango_db_key = key;
	}
	
	
	public abstract void save();
	

	/**
	 * Get the Element's ArangoDB Id.
	 *
	 * @return the id
	 */
	
	public String _id() {
		return arango_db_id;
	}

	/**
	 * Set the Element's ArangoDB Id.
	 *
	 * @param id the id
	 */
	
	public void _id(String id) {
		this.arango_db_id = id;
	}
	
	/**
	 * Get the Element's ArangoDB Key.
	 *
	 * @return the key
	 */
	
	public String _key() {
		return arango_db_key;
	}
	
	/**
	 * Set the Element's ArangoDB Key.
	 *
	 * @param key the key
	 */
	
	public void _key(String key) {
		this.arango_db_key = key;
	}
	
	/**
	 * Get the Element's ArangoDB Revision.
	 *
	 * @return the revision
	 */
	
	public String _rev() {
		return arango_db_rev;
	}

	/**
	 * Set the Element's ArangoDB Revision.
	 *
	 * @param rev the revision
	 */
	
	public void _rev(String rev) {
		this.arango_db_rev = rev;
	}
	
	/**
	 * Collection. When Elements are deserialized from the DB the collection name is recomputed
	 * from the element's id.  
	 *
	 * @return the string
	 */
	
	public String collection() {
		if (arango_db_collection == null) {
			if (arango_db_id != null) {
				arango_db_collection = arango_db_id.split("/")[0];
				int graphLoc = arango_db_collection.indexOf('_');
				arango_db_collection = arango_db_collection.substring(graphLoc+1);
			}
		}
		return arango_db_collection;
	}

	/**
	 * Collection.
	 *
	 * @param collection the collection
	 */

	public void collection(String collection) {
		this.arango_db_collection = collection;
	}
	
	

	/**
	 * Checks if is paired.
	 *
	 * @return true, if is paired
	 */
	
	public boolean isPaired() {
		return paired;
	}

	/**
	 * Sets the paired.
	 *
	 * @param paired the new paired
	 */
	
	public void setPaired(boolean paired) {
		this.paired = paired;
	}

	/**
     * Creates an entry to store the key-value data.
     *
     * @param next  the next entry in sequence
     * @param hashCode  the hash code to use
     * @param key  the key to store
     * @param value  the value to store
     * @return the newly created entry
     */
    protected HashEntry<String, U> createEntry(
    	final HashEntry<String, U> next,
    	final int hashCode,
    	final String key,
    	final U value) {
    	
        return new ArangoDBElementProperty<U>(next, hashCode, convertKey(key), value, this);
    }
	
	@Override
    public boolean equals(final Object object) {
        return ElementHelper.areEqual(this, object);
    }

	@Override
	public Graph graph() {
		return graph;
	}

	/**
	 * Graph.
	 *
	 * @param graph the graph
	 */
	
	public void graph(ArangoDBGraph graph) {
		this.graph = graph;
	}

	@Override
    public int hashCode() {
        return ElementHelper.hashCode(this);
    }
	

	@Override
	public Object id() {
		return arango_db_key;
	}

	@Override
	public Set<String> keys() {
		logger.debug("keys");
		final Set<String> keys = new HashSet<>();
		for (final String key : this.keySet()) {
			if (!Graph.Hidden.isHidden(key))
                keys.add(ArangoDBUtil.denormalizeKey(key));
		}
		return Collections.unmodifiableSet(keys);
	}

    @Override
	public String label() {
		return collection();
	}
    
    protected List<String> getValidProperties(String... propertyKeys) {
		Set<String> validProperties = new HashSet<>(Arrays.asList(propertyKeys));
		validProperties.retainAll(keySet());
		return new ArrayList<>(validProperties);
	}

	@Override
	public U put(String key, U value) {
		if (key == T.id.name()) {
			if (!graph.features().vertex().properties().willAllowId(value)) {
				throw VertexProperty.Exceptions.userSuppliedIdsOfThisTypeNotSupported();
			}
		}
		return super.put(key, value);
	}
	
	@Override
	public boolean remove(Object key, Object value) {
		boolean result = super.remove(key, value);
		if (result) {
			save();
		}
		return result;
	}

}
