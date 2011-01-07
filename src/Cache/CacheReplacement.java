package Cache;

import java.util.Comparator;

public class CacheReplacement {

	public static enum Policy{LRU,BUGS,CHANGES,AUTHORS};
	static final CacheReplacement.Policy REPDEFAULT = CacheReplacement.Policy.LRU;


	protected Comparator<CacheItem> compareFunc;
	
	public CacheReplacement(Policy p){
		switch (p){
		case BUGS: compareFunc = new ComparatorBugs();break;
		case CHANGES: compareFunc = new ComparatorChanges();break;
		case AUTHORS: compareFunc = new ComparatorAuthors();break;
		case LRU: compareFunc = new ComparatorLRU();
		}
	}

	public CacheItem minimum(CacheItem o1, CacheItem o2){
		if (compareFunc.compare(o1, o2) <0 )
			return o1;
		else
			return o2;
	}

}
