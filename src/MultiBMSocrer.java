
import java.io.IOException;
import java.util.ArrayList;

import org.apache.lucene.index.*;
import org.apache.lucene.search.*;

public class MultiBMSocrer extends Scorer{
	private float[] idf;
	private int termCnt ;// the cnt of terms
	private final TermDocs[] termDocs;
	private int nowTerm ; // the index of now term
	private final byte[] norms;
	private float weightValue;
	private int doc = -1;
	private int[] freq;
	private IndexReader reader ;
	private ArrayList<Term> termlist;
	//private Searcher searcher ;
	//public int CNTER = 0 ;

	private final int[] docs = new int[32]; // buffered doc numbers
	private final int[] freqs = new int[32]; // buffered term freqs
	private int pointer;
	private int pointerMax;

	private static final int SCORE_CACHE_SIZE = 32;
	private final float[] scoreCache = new float[SCORE_CACHE_SIZE];

	private float avgLength;
	private float K1 = 2.0f;
	private float b = 0.75f;
	
	public void setBM25Params(float aveParam) {
		avgLength = aveParam;
	}
	
	public void setBM25Params(float aveParam, float kParam, float bParam) {
		avgLength = aveParam;
		K1 = kParam;
		b = bParam;
	}
	
	/**
	 * Construct a <code>SimpleScorer</code>.
	 * 
	 * @param weight
	 *            The weight of the <code>Term</code> in the query.
	 * @param td
	 *            An iterator over the documents matching the <code>Term</code>.
	 * @param similarity
	 *            The </code>Similarity</code> implementation to be used for
	 *            score computations.
	 * @param norms
	 *            The field norms of the document fields for the
	 *            <code>Term</code>.
	 */
	public MultiBMSocrer(Weight weight, TermDocs[] td, Similarity similarity, IndexReader reader, ArrayList<Term> termlist,byte[] norms, float[] idfValues, float avg, int cnt) {
		// TODO Auto-generated constructor stub
		super(similarity, weight) ;
		//this.searcher = search ;
		this.termDocs = td ;
		this.avgLength = avg ;
		this.termCnt = cnt ;
		this.idf = idfValues ;
		this.norms = norms ;
		this.weightValue = weight.getValue() ;
		this.nowTerm = 0 ;
		this.reader = reader ;
		this.termlist = termlist ;
		this.freq = new int[termCnt] ;
		
		for(int i = 0 ; i < SCORE_CACHE_SIZE ; i ++){
			scoreCache[i] = getSimilarity().tf(i) * weightValue ;
		}
	}
	
	@Override
	public void score(Collector c) throws IOException {
		for(int i = 0 ; i < termCnt ; i ++){
			nowTerm = i ;
			//System.out.println("nowTerm: " + nowTerm + "-----------------------");
			score(c, Integer.MAX_VALUE, nextDoc());
		}
	}
	
	// firstDocID is ignored since nextDoc() sets 'doc'
		@Override
		protected boolean score(Collector c, int end, int firstDocID)
				throws IOException {
			c.setScorer(this);
			while (doc < end) { // for docs in window
				c.collect(doc); // collect score

				if (++pointer >= pointerMax) {
					pointerMax = termDocs[nowTerm].read(docs, freqs); // refill buffers
					if (pointerMax != 0) {
						pointer = 0;
					} else {
						termDocs[nowTerm].close(); // close stream
						doc = Integer.MAX_VALUE; // set to sentinel value
						return false;
					}
				}
				doc = docs[pointer];
				freq[nowTerm] = freqs[pointer];
				//System.out.println(doc);
				//update freq of all terms
				for(int i = 0 ; i < termCnt ; i ++){
					if(i == nowTerm) continue ;
					TermDocs tmpDocs = reader.termDocs(termlist.get(i)) ;
					if(tmpDocs == null) continue ;
					try {
						if (tmpDocs.skipTo(doc) && tmpDocs.doc() == doc) {
							freq[i] = tmpDocs.freq();
						}
						else {
							freq[i] = 0 ;
						}
						//System.out.println("freq" + i + ": " + freq[i]) ;
					} catch(Exception e){
						System.out.println("MultiBMSocrer.score()");
					}finally {
						tmpDocs.close();
					}
				}
			}
			return true;
		}

	@Override
	public float score() throws IOException {
		// TODO Auto-generated method stub
		//++ CNTER ;
		//if(CNTER <= 100) System.out.println("BM25 Score for doc: " + doc);
		assert doc != -1 ;
		float BM25Score = 0 ;
		float norm = Similarity.decodeNorm(norms[doc]) ;
		float Dlen = 1/(norm*norm) ;
		for(int i = 0 ; i < termCnt ; i ++){
			float singleq = 0 ;
			singleq = idf[i]*freq[i]*(K1+1)/(freq[i]+K1*(1-b+b*Dlen/avgLength)) ;
			BM25Score += singleq ;
			//if(CNTER <= 100) System.out.println("for term " + i + ": " + idf[i] + ", " + freq[i]);
		}
		//if(freq[0] > 0 && freq[1] > 0) System.out.println(doc + ": " + freq[0] + ": " + freq[1]);
		//System.out.println(CNTER);
		return BM25Score;
	}

	
	/**
	 * Advances to the first match beyond the current whose document number is
	 * greater than or equal to a given target. <br>
	 * The implementation uses {@link TermDocs#skipTo(int)}.
	 * 
	 * @param target
	 *            The target document number.
	 * @return the matching document or NO_MORE_DOCS if none exist.
	 */
	@Override
	public int advance(int target) throws IOException {
		// TODO Auto-generated method stub
		for (pointer++; pointer < pointerMax; pointer++) {
			if (docs[pointer] >= target) {
				freq[nowTerm] = freqs[pointer];
				return doc = docs[pointer];
			}
		}

		// not found in cache, seek underlying stream
		// if no more term
		if(nowTerm >= termCnt){
			doc = NO_MORE_DOCS ;
			return doc ;
		}
		boolean result = termDocs[nowTerm].skipTo(target);
		if (result) {
			pointerMax = 1;
			pointer = 0;
			docs[pointer] = doc = termDocs[nowTerm].doc();
			freqs[pointer] = freq[nowTerm] = termDocs[nowTerm].freq();
		} else {
			doc = NO_MORE_DOCS;
		}
		return doc;
	}

	@Override
	public int docID() {
		// TODO Auto-generated method stub
		return doc;
	}
	
	@Override
	public float freq() {
		return freq[nowTerm];
	}

	@Override
	public int nextDoc() throws IOException {
		// TODO Auto-generated method stub
		if (nowTerm >= termCnt){
			doc = NO_MORE_DOCS ;
			return doc ;
		} // if no more term
		pointer++;
		if (pointer >= pointerMax) {
			pointerMax = termDocs[nowTerm].read(docs, freqs); // refill buffer
			if (pointerMax != 0) {
				pointer = 0;
			} else {
				termDocs[nowTerm].close(); // close stream
				return doc = NO_MORE_DOCS;
			}
		}
		doc = docs[pointer];
		freq[nowTerm] = freqs[pointer];
		return doc;
	}

	/** Returns a string representation of this <code>SimpleScorer</code>. */
	public String toString() {
		return "scorer(" + weight + ")";
	}	
}
