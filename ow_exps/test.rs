fn main() {
    // Create a vector of booleans representing numbers from 2 to 1,000,000.
    // Assume all numbers are prime initially.
    let mut is_prime = vec![true; 1000001];
    // Mark 0 and 1 as non-prime.
    is_prime[0] = false;
    is_prime[1] = false;

    // Iterate through all numbers from 2 to 1,000,000.
    for i in 2..=1000000 {
        // If the current number is prime, mark all its multiples as non-prime.
        if is_prime[i] {
            let mut j = i * i;
            while j <= 1000000 {
                is_prime[j] = false;
                j += i;
            }
        }
    }

    // Collect all prime numbers into a vector and return it.
    let primes: Vec<usize> = (2..=1000000).filter(|&i| is_prime[i]).collect();
    primes
}