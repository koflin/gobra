package trivial;

ensures x <= r && y <= r;
ensures r == x || r == y;
func max(x, y int) (r int) {
  if (x < y) { r = y; }
  else { r = x; };
};

ensures s <= l;
ensures s == x || s == y;
ensures l == x || l == y;
func sort(x, y int) (s, l int) {
  if (x < y) { s = x; l = y; }
  else { s = y; l = x; };
};


func client1() {
  var a, b = 5, 7;
  r := max(a, b);
  assert r == 7;

  /* the following leads to a crash (reported) 
  var s, l int;
  s, l = sort(a, b);
  assert s == 5 && l == 7;
  */
};

/* constants are not supported yet
const five, seven = 5, 7;

func client2() {
  r := max(five, seven);
  assert r == 7;
};
*/

/* uint is not supported yet
func type1(a uint) {
  assert 0 <= a;
};*/

func init() {
  var a int;
  assert a == 0;

  var b bool;
  assert b == false;
};
