package pkg;

pure func foo() int { 
  return 42
}

// invalid trigger: doesn't contain the quantified variable
//:: ExpectedOutput(logic_error)
requires forall x int :: { foo() } 0 < x 
func bar () { }
