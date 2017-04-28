import java.util.ArrayList;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.Explanation.*;
import org.apache.lucene.util.*;





public class MultiBMQuery extends Query{
	private ArrayList<Term> termlist ;
	private int termCnt ;
	private float avgLength ;
	
	public MultiBMQuery(ArrayList<Term> _termlist, float avg, int cnt) {
		// TODO Auto-generated constructor stub
		termlist = _termlist;
		avgLength = avg ;
		termCnt = cnt ;
		System.out.println("Initialize MuiltBMQuery, termCnt = " + termCnt) ;
	}
	
	public ArrayList<Term> getTerms() {
		return termlist ;
	}
	
	private class MultiBMWeight extends Weight{
		private float avgLength;
		private int termCnt ;
		private Searcher search;
		private final Similarity similarity;
		private float value;
		private float[] idf;
		private float queryNorm;
		private float queryWeight;
		private IDFExplanation[] idfExp;
		private final Set<Integer> hash;
		
		public MultiBMWeight (Searcher searcher, float avg, int cnt) 
				throws IOException{
			// TODO Auto-generated constructor stub
			this.avgLength = avg ;
			this.termCnt = cnt ;
			this.search = searcher ;
			idf = new float[cnt] ;
			idfExp = new IDFExplanation[cnt] ;
			this.similarity = getSimilarity(searcher) ;
			if(searcher instanceof IndexSearcher){
				hash = new HashSet<Integer>() ;
				IndexReader ir = ((IndexSearcher) searcher).getIndexReader();
				final int dfSum[] = new int[cnt];
				new ReaderUtil.Gather(ir) {
					
					@Override
					protected void add(int base, IndexReader r) throws IOException {
						//System.out.println("DocFreq in MultiWeight: ");
						// TODO Auto-generated method stub
						for(int i = 0 ; i < termCnt ; i ++){
							int df = r.docFreq(termlist.get(i)) ;
							dfSum[i] += df ;
							//System.out.println("docfreq for " + i + ": " + dfSum[i]);
							if(df > 0){
								hash.add(r.hashCode()) ;
							}
						}
					}	
				}.run();
				for(int i = 0 ; i < termCnt ; i ++){
					idfExp[i] = similarity.idfExplain(termlist.get(i), searcher, dfSum[i]) ;
				}
			}
			else{
				for(int i = 0 ; i < termCnt ; i ++){
					idfExp[i] = similarity.idfExplain(termlist.get(i), searcher) ;
				}
				hash = null ;
			}
			//System.out.println("IDF in MultiWEeight: ");
			for(int i = 0 ; i < termCnt ; i ++){
				idf[i] = idfExp[i].getIdf() ;
				//System.out.println("IDF "+ i + ": " + idf[i]);
			}
		}
			
		@Override
		public String toString() {
			return "weight(" + MultiBMQuery.this + ")";
		}
		
		@Override
		public Explanation explain(IndexReader reader, int doc)
				throws IOException {
			// TODO Auto-generated method stub
			ComplexExplanation result = new ComplexExplanation();
			result.setDescription("weight(" + getQuery() + " in " + doc
					+ "), product of:");
			
			// explain for each term
			for(int i = 0 ; i < termCnt ; i ++){
				Explanation expl = new Explanation(idf[i], idfExp[i].explain());
				// explain query weight
				Explanation queryExpl = new Explanation();
				queryExpl.setDescription("queryWeight(" + getQuery()
						+ "), product of:");
				Explanation boostExpl = new Explanation(getBoost(), "boost");
				if (getBoost() != 1.0f)
					queryExpl.addDetail(boostExpl);
				queryExpl.addDetail(expl);
				Explanation queryNormExpl = new Explanation(queryNorm, "queryNorm");
				queryExpl.addDetail(queryNormExpl);

				queryExpl.setValue(boostExpl.getValue() * expl.getValue()
						* queryNormExpl.getValue());

				result.addDetail(queryExpl);
				// explain field weight
				String field = termlist.get(i).field();
				ComplexExplanation fieldExpl = new ComplexExplanation();
				fieldExpl.setDescription("fieldWeight(" + termlist.get(i) + " in " + doc
						+ "), product of:");
				Explanation tfExplanation = new Explanation();
				int tf = 0;
				TermDocs termDocs = reader.termDocs(termlist.get(i));
				if (termDocs != null) {
					try {
						if (termDocs.skipTo(doc) && termDocs.doc() == doc) {
							tf = termDocs.freq();
						}
					} finally {
						termDocs.close();
					}
					tfExplanation.setValue(similarity.tf(tf));
					tfExplanation.setDescription("tf(termFreq(" + termlist.get(i) + ")=" + tf
							+ ")");
				} else {
					tfExplanation.setValue(0.0f);
					tfExplanation.setDescription("no matching term");
				}
				fieldExpl.addDetail(tfExplanation);
				fieldExpl.addDetail(expl);
				Explanation fieldNormExpl = new Explanation();
				byte[] fieldNorms = reader.norms(field);
				float fieldNorm = fieldNorms != null ? similarity
						.decodeNormValue(fieldNorms[doc]) : 1.0f;
				fieldNormExpl.setValue(fieldNorm);
				fieldNormExpl.setDescription("fieldNorm(field=" + field + ", doc="
						+ doc + ")");
				fieldExpl.addDetail(fieldNormExpl);

				fieldExpl.setMatch(Boolean.valueOf(tfExplanation.isMatch()));
				fieldExpl.setValue(tfExplanation.getValue() * expl.getValue()
						* fieldNormExpl.getValue());
				result.addDetail(fieldExpl);
				result.setMatch(fieldExpl.getMatch());
				// combine them
				result.setValue(queryExpl.getValue() * fieldExpl.getValue());

				if (queryExpl.getValue() == 1.0f)
					//return fieldExpl;
					continue ;
			}		
			return result;
			//return null;
		}

		@Override
		public Query getQuery() {
			return MultiBMQuery.this ;
		}

		@Override
		public float getValue() {
			// TODO Auto-generated method stub
			return value ;
		}

		@Override
		public void normalize(float queryNorm) {
			// TODO Auto-generated method stub
			this.queryNorm = queryNorm ;
			queryWeight *= queryNorm ;
			value = 0 ;
			for(int i = 0 ; i < termCnt ; i ++){
				value += queryWeight * idf[i] ;
			}
		}

		@Override
		public Scorer scorer(IndexReader reader, boolean scoreDocsInOrder, boolean topScorer)
				throws IOException {
			// TODO Auto-generated method stub
			if(hash != null && reader.getSequentialSubReaders() == null && !hash.contains(reader.hashCode())){
				return null ;
			}
			if(termCnt == 0) return null ;
			TermDocs[] termDocs = new TermDocs[termCnt] ;
			for(int i = 0 ; i < termCnt ; i ++){
				termDocs[i] = reader.termDocs(termlist.get(i)) ;
				//System.out.println("termDocs"+termDocs[i].doc());
			}
			Term firsTerm = termlist.get(0) ;
			return new MultiBMSocrer(this, termDocs, similarity, reader, termlist, reader.norms(firsTerm.field()), idf, avgLength, termCnt) ;
		}

		@Override
		public float sumOfSquaredWeights() throws IOException {
			// TODO Auto-generated method stub
			queryWeight = 0 ;
			for(int i = 0 ; i < termCnt ; i ++){
				queryWeight += idf[i] * getBoost() ;
			}
			return queryWeight * queryWeight ;
		}
		
	}
	
	@Override
	public Weight createWeight(Searcher searcher) throws IOException {
		return new MultiBMWeight(searcher,avgLength, termCnt);
	}
	
	@Override
	public void extractTerms(Set<Term> terms) {
		terms.addAll(getTerms()) ;
	}
		
	@Override
	public String toString(String field) {
		// TODO Auto-generated method stub
		StringBuilder buffer = new StringBuilder();
		for(int i = 0 ; i < termCnt ; i ++){
			if(!termlist.get(i).field().equals(field)){
				buffer.append(termlist.get(i).field()) ;
				buffer.append(":") ;
			}
			buffer.append(termlist.get(i).text()) ;
			buffer.append(ToStringUtils.boost(getBoost())) ;
		}
		return buffer.toString();
	}
	
	@Override
	public boolean equals(Object o) {
		if (!(o instanceof MultiBMQuery))
			return false;
		//boolean flag = true ;
		MultiBMQuery other = (MultiBMQuery) o;
		if(this.getBoost() != other.getBoost()) return false ;
		if(this.termCnt != other.termCnt) return false ;
		for(int i = 0 ; i < termCnt ; i ++){
			if(this.termlist.get(i).equals(other.termlist.get(i))) continue ;
			else return false ;
		}
		return true ;
	}
	
	/** Returns a hash code value for this object. */
	@Override
	public int hashCode() {
		int hashcode = 0 ;
		for(int i = 0 ; i < termCnt ; i ++){
			hashcode += Float.floatToIntBits(getBoost()) ^ termlist.get(i).hashCode() ;
		}
		return hashcode ;
	}
}
