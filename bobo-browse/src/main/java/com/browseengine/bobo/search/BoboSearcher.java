package com.browseengine.bobo.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.AtomicReaderContextUtil;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexReaderContext;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;

import com.browseengine.bobo.api.BoboMultiReader;
import com.browseengine.bobo.api.BoboSegmentReader;
import com.browseengine.bobo.docidset.RandomAccessDocIdSet;
import com.browseengine.bobo.facets.FacetCountCollector;
import com.browseengine.bobo.facets.FacetCountCollectorSource;
import com.browseengine.bobo.mapred.BoboMapFunctionWrapper;

public class BoboSearcher extends IndexSearcher {
  protected List<FacetHitCollector> _facetCollectors;
  protected BoboSegmentReader[] _subReaders;

  public BoboSearcher(BoboSegmentReader reader) {
    super(reader);
    _facetCollectors = new LinkedList<FacetHitCollector>();
    List<BoboSegmentReader> readerList = new ArrayList<BoboSegmentReader>();
    readerList.add(reader);
    _subReaders = readerList.toArray(new BoboSegmentReader[readerList.size()]);
  }

  public BoboSearcher(BoboMultiReader reader) {
    super(reader);
    _facetCollectors = new LinkedList<FacetHitCollector>();
    List<BoboSegmentReader> subReaders = reader.getSubReaders();
    _subReaders = subReaders.toArray(new BoboSegmentReader[subReaders.size()]);
  }

  public void setFacetHitCollectorList(List<FacetHitCollector> facetHitCollectors) {
    if (facetHitCollectors != null) {
      _facetCollectors = facetHitCollectors;
    }
  }

  abstract static class FacetValidator {
    protected final FacetHitCollector[] _collectors;
    protected final int _numPostFilters;
    protected FacetCountCollector[] _countCollectors;
    public int _nextTarget;

    private void sortPostCollectors(final BoboSegmentReader reader) {
      Comparator<FacetHitCollector> comparator = new Comparator<FacetHitCollector>() {
        @Override
        public int compare(FacetHitCollector fhc1, FacetHitCollector fhc2) {
          double selectivity1 = fhc1._filter.getFacetSelectivity(reader);
          double selectivity2 = fhc2._filter.getFacetSelectivity(reader);
          if (selectivity1 < selectivity2) {
            return -1;
          } else if (selectivity1 > selectivity2) {
            return 1;
          }
          return 0;
        }
      };

      Arrays.sort(_collectors, 0, _numPostFilters, comparator);
    }

    public FacetValidator(FacetHitCollector[] collectors, int numPostFilters) throws IOException {
      _collectors = collectors;
      _numPostFilters = numPostFilters;
      _countCollectors = new FacetCountCollector[collectors.length];
    }

    /**
     * This method validates the doc against any multi-select enabled fields.
     * @param docid
     * @return true if all fields matched
     */
    public abstract boolean validate(final int docid) throws IOException;

    public void setNextReader(BoboSegmentReader reader, int docBase) throws IOException {
      ArrayList<FacetCountCollector> collectorList = new ArrayList<FacetCountCollector>();
      sortPostCollectors(reader);
      for (int i = 0; i < _collectors.length; ++i) {
        _collectors[i].setNextReader(reader, docBase);
        FacetCountCollector collector = _collectors[i]._currentPointers.facetCountCollector;
        if (collector != null) {
          collectorList.add(collector);
        }
      }
      _countCollectors = collectorList.toArray(new FacetCountCollector[collectorList.size()]);
    }

    public FacetCountCollector[] getCountCollectors() {
      List<FacetCountCollector> collectors = new ArrayList<FacetCountCollector>();
      collectors.addAll(Arrays.asList(_countCollectors));
      for (FacetHitCollector facetHitCollector : _collectors) {
        collectors.addAll(facetHitCollector._collectAllCollectorList);
        collectors.addAll(facetHitCollector._countCollectorList);
      }
      return collectors.toArray(new FacetCountCollector[collectors.size()]);
    }
  }

  private final static class DefaultFacetValidator extends FacetValidator {

    public DefaultFacetValidator(FacetHitCollector[] collectors, int numPostFilters)
        throws IOException {
      super(collectors, numPostFilters);
    }

    /**
     * This method validates the doc against any multi-select enabled fields.
     * @param docid
     * @return true if all fields matched
     */
    @Override
    public final boolean validate(final int docid) throws IOException {
      FacetHitCollector.CurrentPointers miss = null;

      for (int i = 0; i < _numPostFilters; i++) {
        FacetHitCollector.CurrentPointers cur = _collectors[i]._currentPointers;
        int sid = cur.doc;

        if (sid < docid) {
          sid = cur.postDocIDSetIterator.advance(docid);
          cur.doc = sid;
          if (sid == DocIdSetIterator.NO_MORE_DOCS) {
            // move this to front so that the call can find the failure faster
            FacetHitCollector tmp = _collectors[0];
            _collectors[0] = _collectors[i];
            _collectors[i] = tmp;
          }
        }

        if (sid > docid) // mismatch
        {
          if (miss != null) {
            // failed because we already have a mismatch
            _nextTarget = (miss.doc < cur.doc ? miss.doc : cur.doc);
            return false;
          }
          miss = cur;
        }
      }

      _nextTarget = docid + 1;

      if (miss != null) {
        miss.facetCountCollector.collect(docid);
        return false;
      } else {
        for (FacetCountCollector collector : _countCollectors) {
          collector.collect(docid);
        }
        return true;
      }
    }
  }

  private final static class OnePostFilterFacetValidator extends FacetValidator {
    private final FacetHitCollector _firsttime;

    OnePostFilterFacetValidator(FacetHitCollector[] collectors) throws IOException {
      super(collectors, 1);
      _firsttime = _collectors[0];
    }

    @Override
    public final boolean validate(int docid) throws IOException {
      FacetHitCollector.CurrentPointers miss = null;

      RandomAccessDocIdSet set = _firsttime._currentPointers.docidSet;
      if (set != null && !set.get(docid)) {
        miss = _firsttime._currentPointers;
      }

      _nextTarget = docid + 1;

      if (miss != null) {
        miss.facetCountCollector.collect(docid);
        return false;
      } else {
        for (FacetCountCollector collector : _countCollectors) {
          collector.collect(docid);
        }
        return true;
      }
    }
  }

  private final static class NoNeedFacetValidator extends FacetValidator {
    NoNeedFacetValidator(FacetHitCollector[] collectors) throws IOException {
      super(collectors, 0);
    }

    @Override
    public final boolean validate(int docid) throws IOException {
      for (FacetCountCollector collector : _countCollectors) {
        collector.collect(docid);
      }
      return true;
    }

  }

  protected FacetValidator createFacetValidator() throws IOException {

    FacetHitCollector[] collectors = new FacetHitCollector[_facetCollectors.size()];
    FacetCountCollectorSource[] countCollectors = new FacetCountCollectorSource[collectors.length];
    int numPostFilters;
    int i = 0;
    int j = collectors.length;

    for (FacetHitCollector facetCollector : _facetCollectors) {
      if (facetCollector._filter != null) {
        collectors[i] = facetCollector;
        countCollectors[i] = facetCollector._facetCountCollectorSource;
        i++;
      } else {
        j--;
        collectors[j] = facetCollector;
        countCollectors[j] = facetCollector._facetCountCollectorSource;
      }
    }
    numPostFilters = i;

    if (numPostFilters == 0) {
      return new NoNeedFacetValidator(collectors);
    } else if (numPostFilters == 1) {
      return new OnePostFilterFacetValidator(collectors);
    } else {
      return new DefaultFacetValidator(collectors, numPostFilters);
    }
  }

  @Override
  public void search(Query query, Filter filter, Collector collector) throws IOException {
    Weight weight = createNormalizedWeight(query);
    search(weight, filter, collector, 0, null);
  }

  public void search(Weight weight, Filter filter, Collector collector, int start,
      BoboMapFunctionWrapper mapReduceWrapper) throws IOException {
    final FacetValidator validator = createFacetValidator();
    int target = 0;

    IndexReader reader = getIndexReader();
    IndexReaderContext indexReaderContext = reader.getContext();
    if (filter == null) {
      for (int i = 0; i < _subReaders.length; i++) {
        AtomicReaderContext atomicContext = indexReaderContext.children() == null ? (AtomicReaderContext) indexReaderContext
            : (AtomicReaderContext) (indexReaderContext.children().get(i));
        int docStart = start;
        
        atomicContext = AtomicReaderContextUtil.updateDocBase(atomicContext, docStart);
        
        if (reader instanceof BoboMultiReader) {
          docStart = start + ((BoboMultiReader) reader).subReaderBase(i);
        }
        collector.setNextReader(atomicContext);
        validator.setNextReader(_subReaders[i], docStart);

        Scorer scorer = weight.scorer(atomicContext, true, true, _subReaders[i].getLiveDocs());
        if (scorer != null) {
          collector.setScorer(scorer);
          target = scorer.nextDoc();
          while (target != DocIdSetIterator.NO_MORE_DOCS) {
            if (validator.validate(target)) {
              collector.collect(target);
              target = scorer.nextDoc();
            } else {
              target = validator._nextTarget;
              target = scorer.advance(target);
            }
          }
        }
        if (mapReduceWrapper != null) {
          mapReduceWrapper.mapFullIndexReader(_subReaders[i], validator.getCountCollectors());
        }
      }
      return;
    }

    for (int i = 0; i < _subReaders.length; i++) {
      AtomicReaderContext atomicContext = indexReaderContext.children() == null ? (AtomicReaderContext) indexReaderContext
          : (AtomicReaderContext) (indexReaderContext.children().get(i));

      DocIdSet filterDocIdSet = filter.getDocIdSet(atomicContext, _subReaders[i].getLiveDocs());
      if (filterDocIdSet == null) return; // shall we use return or continue here ??
      int docStart = start;
      if (reader instanceof BoboMultiReader) {
        docStart = start + ((BoboMultiReader) reader).subReaderBase(i);
      }
      collector.setNextReader(atomicContext);
      validator.setNextReader(_subReaders[i], docStart);
      Scorer scorer = weight.scorer(atomicContext, true, false, _subReaders[i].getLiveDocs());
      if (scorer != null) {
        collector.setScorer(scorer);
        DocIdSetIterator filterDocIdIterator = filterDocIdSet.iterator(); // CHECKME: use
                                                                          // ConjunctionScorer here?

        if (filterDocIdIterator == null) continue;

        int doc = -1;
        target = filterDocIdIterator.nextDoc();
        if (mapReduceWrapper == null) {
          while (target < DocIdSetIterator.NO_MORE_DOCS) {
            if (doc < target) {
              doc = scorer.advance(target);
            }

            if (doc == target) // permitted by filter
            {
              if (validator.validate(doc)) {
                collector.collect(doc);

                target = filterDocIdIterator.nextDoc();
              } else {
                // skip to the next possible docid
                target = filterDocIdIterator.advance(validator._nextTarget);
              }
            } else // doc > target
            {
              if (doc == DocIdSetIterator.NO_MORE_DOCS) break;
              target = filterDocIdIterator.advance(doc);
            }
          }
        } else {
          // MapReduce wrapper is not null
          while (target < DocIdSetIterator.NO_MORE_DOCS) {
            if (doc < target) {
              doc = scorer.advance(target);
            }

            if (doc == target) // permitted by filter
            {
              if (validator.validate(doc)) {
                mapReduceWrapper.mapSingleDocument(doc, _subReaders[i]);
                collector.collect(doc);

                target = filterDocIdIterator.nextDoc();
              } else {
                // skip to the next possible docid
                target = filterDocIdIterator.advance(validator._nextTarget);
              }
            } else // doc > target
            {
              if (doc == DocIdSetIterator.NO_MORE_DOCS) break;
              target = filterDocIdIterator.advance(doc);
            }
          }
          mapReduceWrapper.finalizeSegment(_subReaders[i], validator.getCountCollectors());
        }
      }
    }

  }
}
