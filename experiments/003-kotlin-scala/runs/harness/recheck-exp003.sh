B=$HOME/experiments/langbench-exp003; set -a; source $B/bench.env; set +a
echo run,mode,trial,succ,reqs,time > $B/recheck.csv
for r in $(cat $B/run-order.txt); do for t in 1 2; do
  h=$(bash $B/measure.sh $B/app-$r $B/harness-hurl 2>>$B/recheck.err | tail -1); echo "$r,host,$t,$h,$(date +%T)" >> $B/recheck.csv
  d=$(bash $B/measure_docker.sh $B/app-$r $r-recheck $B/harness-hurl 2>>$B/recheck.err | tail -1); echo "$r,docker,$t,$d,$(date +%T)" >> $B/recheck.csv
done; done; touch $B/recheck-done
