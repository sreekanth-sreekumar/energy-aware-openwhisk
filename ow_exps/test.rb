def main
  # Create an array of booleans representing numbers from 2 to 1,000,000.
  # Assume all numbers are prime initially.
  is_prime = Array.new(1000001, true)
  # Mark 0 and 1 as non-prime.
  is_prime[0] = false
  is_prime[1] = false

  # Iterate through all numbers from 2 to 1,000,000.
  (2..1000000).each do |i|
    # If the current number is prime, mark all its multiples as non-prime.
    if is_prime[i]
      (i * i..1000000).step(i) do |j|
        is_prime[j] = false
      end
    end
  end

  # Collect all prime numbers into an array and return it.
  primes = []
  (2..1000000).each do |i|
    primes << i if is_prime[i]
  end

  { "output" => "done" }
end
